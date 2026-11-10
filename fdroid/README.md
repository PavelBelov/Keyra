# F-Droid submission preparation

This directory contains a **submission draft**, not a published F-Droid package.
The metadata, licenses, and recipe are prepared locally; no release tag, GitHub
Release, or GitLab merge request is created by this preparation.

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

`Binaries` points to the planned immutable asset:

`https://github.com/PavelBelov/Keyra/releases/download/v0.1.0/keyra-0.1.0.apk`

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
all 69 database icons passed provenance and byte-identical re-render checks.

```powershell
.\scripts\build.ps1 -Tasks @('-I', 'scripts/fdroid-audit.init.gradle', ':app:fdroidAudit')
.\scripts\export-dependency-licenses.ps1
node scripts/check-vault-icons.cjs --render
node scripts/validate-fdroid.cjs
```

The last two commands use local developer tooling. Install the pinned packages
if absent; neither is a runtime dependency or part of the F-Droid Android build:

```powershell
npm install --prefix .tools/icon-converter --no-audit --no-fund @resvg/resvg-js@2.6.2
npm install --prefix .tools/fdroid-validation --no-audit --no-fund --ignore-scripts yaml@2.8.1
```

The YAML/length/hash checks are local structural validation. Native `fdroid lint`,
the F-Droid scanner, Linux assembly, and reproducibility comparison are separate
next-stage checks and have not been represented as completed here.

## Before submitting the merge request

1. Review the descriptions and screenshots. Optional improvements are Russian
   device screenshots and a dedicated project/privacy page; no placeholder
   contact information has been invented.
2. Commit the preparation and corrected legal notices. Confirm the repository
   can be cloned publicly without authentication and source checkout builds
   without `.tools`, local SDK paths, or signing secrets.
3. Choose the final release commit and create immutable tag `v0.1.0`. The draft
   recipe deliberately refers to this future tag. Pinning its exact commit hash
   in the submitted build entry is also supported.
4. Rebuild and sign a fresh release APK from that exact source with the permanent
   key whose fingerprint is listed above. The earlier APK lacks the newly added
   legal notices and therefore must not be reused as the reference for this tag.
5. Verify reproducibility in a provisioned Linux/F-Droid build environment and
   resolve differences in environment/toolchain before publishing. Compare the
   unsigned build to the signed reference using F-Droid's signature-copying
   verification; raw signed/unsigned file hashes are expected to differ.
6. Publish the signed APK as `keyra-0.1.0.apk` in the `v0.1.0` GitHub Release.
   Record its separate file SHA-256 and retain the R8 mapping for diagnostics.
7. In a fork of [fdroiddata](https://gitlab.com/fdroid/fdroiddata), copy the recipe,
   run `fdroid rewritemeta xnet13.keyra` and `fdroid lint xnet13.keyra`. In a
   provisioned build-server setup, run
   `fdroid build --server --test --verbose xnet13.keyra:1` and inspect scanner and
   reproducibility results. Submit the reviewed recipe as a merge request.

References: [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[build metadata](https://f-droid.org/docs/Build_Metadata_Reference/),
[developer-signed reproducible builds](https://f-droid.org/docs/Reproducible_Builds/).
