# Release dependency and artwork audit

Reviewed on November 10, 2026 for Keyra 0.1.0, version code 1, package `xnet13.keyra`.

## Runtime artifacts

The Gradle `releaseRuntimeClasspath` was resolved rather than inferred from direct dependency declarations. It selects 67 unique external JAR/AAR artifacts: 66 declaring Apache-2.0 and Kotpass 0.13.0 declaring MIT. Identical repeated selections of the same coordinate, filename, type, and hash are counted once. No proprietary advertising, analytics, Google Play Services, Firebase, or Billing SDK is present in this resolved graph. `com.google.guava:listenablefuture:1.0` is a free Apache-2.0 library, not Google Play Services.

[dependency-audit.json](dependency-audit.json) records each selected coordinate, filename/type, SHA-256 of the downloaded artifact, primary POM URL/hash, declared license name, and license POM URL/hash. Guava ListenableFuture has no local license declaration and inherits Apache-2.0 from `com.google.guava:guava-parent:26.0-android`; the report resolves that parent explicitly. All other artifacts declare licenses in their own POM.

The resolved stdlib version is 2.1.21 even though the Kotlin compiler plugin is 2.1.20. Okio resolves to 3.15.0 via Kotpass. Neither should be reported merely as the versions requested by a direct dependency.

These licenses permit redistribution in the GPLv3-based application subject to preserving their terms and notices. The graph is measured before R8 shrinking; artifact presence does not imply all of its code is retained in the final APK. This inventory excludes build/test tooling, which is not bundled as runtime code.

## Source embedded inside Kotpass

Kotpass embeds code which is not represented as separate runtime Maven dependencies. The source was checked against tag `0.13.0`, commit [`4e0bd6b6b13e59d48ebe0bd9c06de3f918de6945`](https://github.com/keemobile/kotpass/tree/4e0bd6b6b13e59d48ebe0bd9c06de3f918de6945).

| Component | Evidence and distribution notice |
| --- | --- |
| Kotpass itself | MIT; upstream LICENSE.txt blob `7edc3ca86a52c8a31fa7744889a3992c89e46f85` matches the existing APK asset |
| Apache Commons Lang | Apache-2.0; upstream NOTICE blob `207b56a1f58d695a28dd92b0bf8d9bff87a7e42a` matches Apache-Commons-NOTICE.txt |
| kotlin-xml-builder | Apache-2.0; vendored LICENSE.txt blob `8dada3edaf50dbc082c9a125058f25def75e625a`; complete Apache-2.0 text is already shipped |
| Bouncy Castle-derived cryptography | Permissive Bouncy Castle / MIT-style license; copyright and permission text are preserved in Bouncy-Castle.txt |
| Base64 implementation | Apache-2.0 header and author credit to Alexander Y. Kleymenov in `app/keemobile/kotpass/io/Base64.kt`; attribution supplied in Apache-Base64-NOTICE.txt |

The vendored components are covered separately from the MIT declaration in the Kotpass POM. Their exact standalone versions are not separately declared by Kotpass, so this audit identifies the containing release/source revision rather than inventing versions.

## Database artwork

The 68 third-party database icons originate from SVG files that, during the original audit, matched the Git blob hashes from [KeePassXC revision 6bcb901aefdd6c7a525466d7dfe099c5ebc13c0e](https://github.com/keepassxreboot/keepassxc/tree/6bcb901aefdd6c7a525466d7dfe099c5ebc13c0e/share/icons/database), after normalizing text line endings to LF. The upstream COPYING blob was `a00aaf28c357bc0b9319103c02d3fee891d2c673` and also matched. The SVG originals and converter have since been removed from this repository; the packaged PNG resources remain.

- 56 icons: MIT, including the Icons8 flat-color collection and five KeePassXC replacements.
- 12 icons: CC0-1.0, from small-n-flat.
- Database icon 64 (vault_icon_64.png) was independently redrawn by a Keyra contributor and is original project artwork under GPL-3.0-or-later; it is excluded from the third-party inventory.

[vault-icon-provenance.json](vault-icon-provenance.json) records every upstream SVG filename/hash, packaged PNG, and license. During the original audit, rendering the 68 retained upstream icons using resvg 2.6.2 at width 112 produced **68/68 byte-identical PNG files**. This historical result is not a check run by the current build.

The repository retains the per-file inventory and relevant upstream notices; the APK ships `KeePassXC-Icons-NOTICE.txt` plus the full MIT and CC0 texts. These notices supplement, rather than replace, the license of Keyra's original code and application logo.

## Scope and remaining release checks

The audit scope is the selected runtime graph, declared POM licenses including parent inheritance, known embedded source notices, and the provenance of the database artwork. F-Droid maintainers will perform their own review of the source and build inputs.

On November 12, 2026, Docker/Linux preflight was performed with native fdroidserver 2.4.5. Source scans of the release tag and current HEAD and binary scans passed without scanner ignores or blanket license exceptions. Independent Linux and F-Droid builds produced identical unsigned release APKs. The published signed `v0.1.0` reference failed signature-copy verification because the signing step rewrote ZIP alignment; the workflow was corrected and passed a disposable-key test. A new real signed release and its comparison remain required before submission. See [the dated results and limits](verification/RESULTS-2026-11-12.md).

Because new legal notices are included in APK assets, the earlier signed `.release/release/keyra.apk` is not the final reference APK for these updated sources. Rebuild from the final release commit and sign it with the same permanent key before publishing the GitHub Release. Do not reuse the earlier APK while claiming it matches these sources.
