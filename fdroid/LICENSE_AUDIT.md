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

All 69 `images/vault-icons/icons/Cxx_*.svg` files match the Git blob hashes from [KeePassXC revision 6bcb901aefdd6c7a525466d7dfe099c5ebc13c0e](https://github.com/keepassxreboot/keepassxc/tree/6bcb901aefdd6c7a525466d7dfe099c5ebc13c0e/share/icons/database), after normalizing text line endings to LF. The upstream COPYING blob is `a00aaf28c357bc0b9319103c02d3fee891d2c673` and also matches.

- 56 icons: MIT, including the Icons8 flat-color collection and five KeePassXC replacements.
- 12 icons: CC0-1.0, from small-n-flat.
- C64_Apple.svg: upstream annotates `GPL-2+` in its Copyright field and omits a separate License field. The original annotation is retained verbatim and conservatively handled as GPL-2.0-or-later. It is not presented as Keyra-owned artwork or relicensed as MIT.

[PROVENANCE.json](../images/vault-icons/PROVENANCE.json) maps every source SVG, packaged PNG, and license. Rendering all 69 original SVGs using resvg 2.6.2 at width 112 produced **69/69 byte-identical PNG files**. The converter paths were repaired after the source folder had moved; no existing icon PNG was changed.

The previous project notice omitted this artwork's attribution. The source now includes the upstream COPYING and per-file inventory; the APK now ships `KeePassXC-Icons-NOTICE.txt` plus the full MIT, CC0, and GPLv2 texts. These notices supplement, rather than replace, the license of Keyra's original code and application logo.

## Scope and remaining release checks

The audit scope is the selected runtime graph, declared POM licenses including parent inheritance, known embedded source notices, and the provenance of the database artwork. F-Droid maintainers will perform their own review of the source and build inputs.

The F-Droid server scanner and an isolated Linux build/reproducibility comparison remain required before submission. The local machine has no general-purpose WSL Linux distribution and its Docker daemon is not running; a successful Windows APK build does not replace those checks. No scanner ignores or blanket license exceptions were added to bypass review.

Because new legal notices are included in APK assets, the earlier signed `.release/release/keyra.apk` is not the final reference APK for these updated sources. Rebuild from the final release commit and sign it with the same permanent key before publishing the GitHub Release. Do not reuse the earlier APK while claiming it matches these sources.
