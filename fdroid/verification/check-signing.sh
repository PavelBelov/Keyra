#!/usr/bin/env bash
# Disposable key only: tests the packaging behavior, never signs a real release.
set -euo pipefail
unsigned=$1
output=$2
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 1 \
    -dname 'CN=Disposable reproducibility test' -storetype PKCS12 \
    -keystore "$scratch/fixture.p12" -storepass fixture-password -keypass fixture-password
apksigner sign --ks "$scratch/fixture.p12" --ks-key-alias fixture \
    --ks-pass pass:fixture-password --key-pass pass:fixture-password \
    --alignment-preserved true --v1-signing-enabled false --v2-signing-enabled true \
    --v3-signing-enabled false --v4-signing-enabled false \
    --out "$output/alignment-preserved-fixture.apk" "$unsigned"
python3 /checks/compare-apks.py "$output/alignment-preserved-fixture.apk" "$unsigned" \
    "$output/alignment-preserved-smoke.json" --signed-reference
python3 - "$output/alignment-preserved-fixture.apk" "$unsigned" <<'PY'
import argparse
import sys
import tempfile
from fdroidserver import common
signed_apk, unsigned_apk = sys.argv[1:]
sys.argv = sys.argv[:1]
parser = argparse.ArgumentParser()
common.setup_global_opts(parser)
common.parse_args(parser)
common.read_config()
with tempfile.TemporaryDirectory() as temporary:
    error = common.verify_apks(signed_apk, unsigned_apk, temporary)
    if error:
        raise SystemExit(error)
print('Native fdroidserver.common.verify_apks: PASSED with --alignment-preserved true')
PY
