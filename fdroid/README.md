# F-Droid submission preparation

This directory contains a **submission draft**, not a published F-Droid package.
The metadata, licenses, and recipe are prepared locally; no release tag, GitHub
Release, or GitLab merge request is created by this preparation.

Linux/F-Droid preflight was performed on November 12, 2026. Compilation, native
lint/scanners, 55 JVM tests, and repeatability of the current HEAD passed.
The published `v0.1.0` reference fails signature-copy verification because its
signing step rewrote ZIP alignment. The Release workflow now preserves alignment;
a disposable-key test passed F-Droid's verifier. A new real signed release and
its verification are required before submission. See the
[results](verification/RESULTS-2026-11-12.md) and
[repeatable environment/instructions](verification/README.md).

The GitHub Actions release workflow can build, sign, and publish the reference APK
after a version tag is pushed. The command
`sh ./gradlew prepareRelease -PreleaseVersion=VERSION` updates the
committed version, appends a recipe build entry, and creates localized changelogs.
It does not submit changes to fdroiddata or verify buildserver reproducibility.

## Files

- `metadata/xnet13.keyra.yml`: copy into `metadata/` of a fork of fdroiddata.
- `dependency-audit.json`: exact selected release runtime artifacts and POM license evidence.
- `LICENSE_AUDIT.md`: audit method, embedded code, artwork findings, and remaining checks.
- `../fastlane/metadata/android/en-US/` and `ru-RU/`: localized store text, version-code changelogs, and launcher icons.
- English phone screenshots show synthetic QA data from the actual Keyra UI. No real database or personal passwords were used. They are existing Android 17 test captures, not newly captured Russian screenshots; localized Russian interface captures can be added before submission.

The icon is a 512x512 raster export of the current adaptive launcher artwork.
The Android application version and display name come from `app-info.properties`.
Author email and a separate website are omitted: email was not provided, and
F-Droid's `WebSite` field should not duplicate the source-repository URL.

## Build recipe

The current app has one full-featured release variant and no proprietary payment,
advertising, or analytics SDK. It does not need a dedicated F-Droid flavor yet.
The recipe builds the `app` module using Gradle 8.11.1, AGP 8.10.1, OpenJDK 17,
Android SDK Platform 36, and explicitly pinned Build Tools 35.0.0.

The standard F-Droid VM is expected to provide its OpenJDK 17 alternative
`java-1.17.0-openjdk-amd64`; the recipe selects it with `sudo`. A local Linux build
must select OpenJDK 17 via JAVA_HOME as well. The VM command remains to be tested
in the target build environment.

`Binaries` currently points to the published first-release asset:

`https://github.com/PavelBelov/Keyra/releases/download/v0.1.0/keyra-0.1.0.apk`

This existing APK is not a passing reproducible reference; see the result above.
Do not replace that asset or move the tag to conceal the failure. The next
version must include the corrected signing workflow and pass comparison anew.

`AllowedAPKSigningKeys` contains the **certificate** SHA-256 read from the existing
signed `.release/release/keyra.apk`:

`c534c1fa0403986e4a61c24fef8ebdfc0625ca0b991ec765ae6354ec4fed8cea`

It is the certificate fingerprint, not the SHA-256 of the APK file. The signer is
CN=xNet13, RSA 4096. No private key, keystore, or signing password is required by
this recipe. F-Droid builds an unsigned APK and verifies it against the
developer-signed reference before publishing the developer's signature.

Auto-update checks are intentionally disabled for this first submission. Versions
are read from a custom properties file through Gradle functions; the standard
source-version detector has not been verified against this layout. Add build
entries manually until tag-based checks are configured and tested. Do not claim
automatic updates solely because a tag exists.

## Checks performed locally

On November 10, 2026, `:app:assembleRelease` and `:app:lintDebug` completed
successfully on Windows. Lint reported no errors and 11 warnings. The unsigned
release APK contains all 11 license/notice assets and reports package
`xnet13.keyra`, version `0.1.0` (1), minSdk 30, and targetSdk 36. Local metadata
validation passed for both languages and all 67 unique runtime artifact records;
the 68 retained third-party database icons passed provenance and byte-identical re-render checks. Database icon 64 has since been independently redrawn as original Keyra artwork.

```powershell
.\gradlew.bat exportDependencyLicenses
.\gradlew.bat validateFdroid
```

The last command runs the project-specific validation task. On Linux/macOS use:

```sh
sh ./gradlew validateFdroid
```

To regenerate the license report on Linux/macOS, use
`sh ./gradlew exportDependencyLicenses`. The single task
`:app:exportDependencyLicenses` in `scripts/dependency-audit.gradle` resolves release
artifacts and downloads missing POMs through Gradle without intermediate JSON. It overwrites
`fdroid/dependency-audit.json` only after all licenses have been resolved and reviewed
by the script's allowlist. No PowerShell or manual Gradle cache path is needed.

It requires the normal JDK/Gradle build environment; Node.js and npm are not needed.
Gradle downloads the pinned YAML parser automatically on the first run. This is a
build-tool dependency only and is not packaged into the app.

The YAML/length/hash checks are local structural validation. Native F-Droid and
Linux checks are documented separately in [the dated results](verification/RESULTS-2026-11-12.md),
including the failed comparison of the existing signed `v0.1.0` APK. The official
buildserver VM/CI and comparison of the next real signed release remain pending.

## Before submitting the merge request

1. Review the descriptions and screenshots. Optional improvements are Russian
   device screenshots and a dedicated project/privacy page; no placeholder
   contact information has been invented.
2. Commit the preparation and corrected legal notices. Confirm the repository
   can be cloned publicly without authentication and source checkout builds
   without `.tools`, local SDK paths, or signing secrets.
3. Prepare the next version (for example `0.1.1`) with
   `sh ./gradlew prepareRelease -PreleaseVersion=0.1.1`, fill both changelogs,
   and commit it together with the corrected Release workflow. Use a new immutable
   tag. `v0.1.0` already exists and must not be moved.
4. Build/sign that exact tag with the permanent key whose fingerprint is listed
   above, using `apksigner --alignment-preserved true`. The fixed GitHub Release
   workflow does this. No replacement signing key is needed.
5. Publish the next signed reference APK via that workflow, then run the
   [Linux/F-Droid checks](verification/README.md) against its actual public asset.
   A disposable-key smoke test does not replace this last comparison.
6. For the first fdroiddata submission, include the passing new build entry;
   remove or explicitly disable the old failing `0.1.0` entry before submission.
   Review MaintainerNotes, record the APK file SHA-256, and retain the R8 mapping.
7. In a fork of [fdroiddata](https://gitlab.com/fdroid/fdroiddata), copy the recipe,
   run `fdroid rewritemeta xnet13.keyra` and `fdroid lint xnet13.keyra`. In a
   provisioned build-server setup, run
   `fdroid build --server --test --verbose xnet13.keyra:1` and inspect scanner and
   reproducibility results. Submit the reviewed recipe as a merge request.

References: [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[build metadata](https://f-droid.org/docs/Build_Metadata_Reference/),
[developer-signed reproducible builds](https://f-droid.org/docs/Reproducible_Builds/).
