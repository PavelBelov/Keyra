# Third-party components

Keyra's own code is licensed under `GPL-3.0-or-later`; see [LICENSING.md](LICENSING.md). The components below retain their own licenses and notices.

The release runtime dependency audit is in [fdroid/dependency-audit.json](fdroid/dependency-audit.json), with every selected external artifact, version, SHA-256, declared license, POM URL/hash, and inherited-license evidence. The current graph contains **67 unique external artifacts: 66 Apache-2.0 and 1 MIT**, after deduplicating identical artifact selections. This is the classpath before R8 shrinking, not a count of libraries whose code survives in the APK. See [the audit scope and findings](fdroid/LICENSE_AUDIT.md).

| Component | License / terms |
| --- | --- |
| Kotlin stdlib 2.1.21 and JDK bridges 1.8.22; coroutines 1.10.2; serialization-core-jvm 1.7.3 | Apache-2.0 |
| AndroidX | Apache-2.0 |
| OkHttp, Okio | Apache-2.0 |
| Kotpass 0.13.0 | MIT; includes Apache Commons / kotlin-xml-builder code (Apache-2.0) and Bouncy Castle cryptographic components under their permissive license |
| Guava ListenableFuture 1.0 | Apache-2.0, inherited from guava-parent 26.0-android |
| JetBrains annotations 23.0.0; JSpecify 1.0.0 | Apache-2.0 |
| 69 database icons from KeePassXC | 56 MIT, 12 CC0-1.0, and 1 GPL-2.0-or-later annotation; see [provenance and notices](images/vault-icons/README.md) |
| JUnit, MockWebServer | Test dependencies only; not included in the app |

Kotpass source and license: [keemobile/kotpass](https://github.com/keemobile/kotpass).
The local `.tools/` directory contains development tools and is excluded from both the app and Git.

The APK includes Kotpass, Apache, Bouncy Castle, and database-icon license texts and notices in `assets/licenses/`. The original database SVGs and their upstream notice are included in the source repository. Keyra's own application logo is covered by the project's licensing declaration.

To refresh the runtime audit with the prepared local toolchain:

```powershell
.\scripts\build.ps1 -Tasks @('-I', 'scripts/fdroid-audit.init.gradle', ':app:fdroidAudit')
.\scripts\export-dependency-licenses.ps1
```

For a different Gradle cache, pass `-GradleCachePath` pointing to its `caches/modules-2/files-2.1` directory. A new or unresolved license fails the export and must be reviewed explicitly. The resvg 2.6.2 SVG converter (MPL-2.0), JUnit, and MockWebServer are development/test tooling and are not runtime dependencies of the app.
