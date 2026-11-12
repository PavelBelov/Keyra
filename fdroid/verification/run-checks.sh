#!/usr/bin/env bash
# Mount only the public Git bundle/reference APK/recipe at /input, scripts at
# /checks (both read-only), an output folder at /output, and a volume at /work.
set -euo pipefail
# Execute a private copy so editing host scripts cannot change a running check.
if [ "${KEYRA_CHECK_SNAPSHOT:-}" != yes ]; then
    snapshot=$(mktemp /work/keyra-check-script.XXXXXX)
    cp "$0" "$snapshot"
    export KEYRA_CHECK_SNAPSHOT=yes
    exec bash "$snapshot"
fi
export GRADLE_OPTS='-Dorg.gradle.workers.max=2 -Dorg.gradle.daemon=false'
job=$(mktemp -d /work/keyra-verify.XXXXXX)
output="/output/$(basename "$job")"
mkdir -p "$output"
status="$output/checks.tsv"
printf 'check\texit_code\n' > "$status"
run_check() {
    local name=$1
    shift
    echo "=== $name ==="
    set +e
    "$@" 2>&1 | tee "$output/$name.log"
    local code=${PIPESTATUS[0]}
    set -e
    printf '%s\t%s\n' "$name" "$code" >> "$status"
    return 0
}

{
    date -u --iso-8601=seconds
    uname -a
    cat /etc/os-release
    java -version
    fdroid --version
    /opt/gradle-8.11.1/bin/gradle --version
    pip freeze
    dpkg-query -W openjdk-17-jdk-headless
    cat /opt/android-sdk/build-tools/35.0.0/source.properties
} > "$output/environment.txt" 2>&1

git clone /input/keyra.bundle "$job/current"
git clone /input/keyra.bundle "$job/release"
tag=$(python3 -c 'import json; print(json.load(open("/input/upstream-release.json"))["tag"])')
reference=$(python3 -c 'import json; print(json.load(open("/input/upstream-release.json"))["apk"])')
expected_commit=$(python3 -c 'import json; print(json.load(open("/input/upstream-release.json"))["commit"])')
application_id=$(python3 -c 'import json; print(json.load(open("/input/upstream-release.json"))["applicationId"])')
version_code=$(python3 -c 'import json; print(json.load(open("/input/upstream-release.json"))["code"])')
git -C "$job/release" checkout --detach "$tag"
test "$(git -C "$job/release" rev-parse HEAD)" = "$expected_commit"
{
    echo "work=$job"
    echo "release_tag=$tag"
    echo "release_commit=$expected_commit"
    echo "current_commit=$(git -C "$job/current" rev-parse HEAD)"
} > "$output/sources.txt"
(cd /input && sha256sum --check SHA256SUMS)
cp "/input/$reference" "$output/reference.apk"
apksigner verify --print-certs "$output/reference.apk" > "$output/reference-certificate.txt"
expected_certificate=$(python3 -c 'import json; print(json.load(open("/input/upstream-release.json"))["certificateSha256"])')
actual_certificate=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$output/reference-certificate.txt")
test "$actual_certificate" = "$expected_certificate"
grep -q -- "$expected_certificate" "/input/$application_id.yml"

mkdir -p "$job/fdroid/metadata" "$job/fdroid/config"
cp "/input/$application_id.yml" "$job/fdroid/metadata/"
python3 /checks/fetch-catalog.py 1efffc05164ce92b374ef44a64be111e70818617 \
    "$job/fdroid/config" "$output/fdroiddata-catalog.json"
cat > "$job/fdroid/config.yml" <<'YAML'
sdk_path: /opt/android-sdk
gradle: /opt/gradle-8.11.1/bin/gradle
apksigner: /opt/android-sdk/build-tools/35.0.0/apksigner
scan_binary: true
YAML
cd "$job/fdroid"
run_check fdroid-lint fdroid lint "$application_id"
run_check published-apk-scan fdroid scanner --exit-code "$output/reference.apk"
run_check fdroid-source-scan fdroid scanner --refresh --exit-code "$application_id:$version_code"
run_check fdroid-build fdroid build --test --stop --scan-binary --verbose "$application_id:$version_code"
if [ -f "tmp/${application_id}_${version_code}.apk" ]; then
    cp "tmp/${application_id}_${version_code}.apk" "$output/fdroid-unsigned.apk"
    run_check alignment-preserved-smoke bash /checks/check-signing.sh "$output/fdroid-unsigned.apk" "$output"
    run_check fdroid-reference-comparison python3 /checks/compare-apks.py \
        "$output/reference.apk" "$output/fdroid-unsigned.apk" \
        "$output/fdroid-reference-comparison.json" --signed-reference
fi

# Independently build the exact release checkout, even when recipe/scanner fails.
cd "$job/release"
run_check linux-release-build bash ./gradlew --no-daemon --console=plain --max-workers=2 :app:assembleRelease
if [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
    cp app/build/outputs/apk/release/app-release-unsigned.apk "$output/linux-release-unsigned.apk"
    if [ -f "$output/fdroid-unsigned.apk" ]; then
        run_check linux-vs-fdroid python3 /checks/compare-apks.py \
            "$output/fdroid-unsigned.apk" "$output/linux-release-unsigned.apk" "$output/linux-vs-fdroid.json"
    fi
    run_check linux-release-comparison python3 /checks/compare-apks.py \
        "$output/reference.apk" "$output/linux-release-unsigned.apk" \
        "$output/linux-release-comparison.json" --signed-reference
fi

cd "$job/current"
run_check fdroid-current-source-scan python3 /checks/scan-current.py \
    "$job/current" "/input/$application_id.yml" "$job/fdroid/config.yml" "$job/current-source-scan"
run_check linux-current-build bash ./gradlew --no-daemon --console=plain --max-workers=2 \
    checkRelease validateFdroid :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
if [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
    cp app/build/outputs/apk/release/app-release-unsigned.apk "$output/current-unsigned.apk"
    if [ -d app/build/reports ]; then cp -r app/build/reports "$output/current-app-reports"; fi
    if [ -d core/build/reports ]; then cp -r core/build/reports "$output/current-core-reports"; fi
    run_check linux-current-rebuild bash ./gradlew --no-daemon --console=plain --max-workers=2 \
        --no-build-cache --rerun-tasks :app:assembleRelease
    run_check linux-current-repeatability python3 /checks/compare-apks.py \
        "$output/current-unsigned.apk" app/build/outputs/apk/release/app-release-unsigned.apk \
        "$output/current-repeatability.json"
fi
echo "Reports: $output; Linux workspace: $job"
cat "$status"
awk -F '\t' 'NR > 1 && $2 != 0 { failed=1 } END { exit failed }' "$status"
