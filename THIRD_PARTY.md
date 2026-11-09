# Third-party components

Keyra's own code is licensed under `GPL-3.0-or-later`; see [LICENSING.md](LICENSING.md). The components below retain their own licenses and notices.

Exact versions of direct dependencies are pinned in Gradle. Before publication, generate a complete list of transitive components from `releaseRuntimeClasspath` and retain their required notices.

| Component | License / terms |
| --- | --- |
| Kotlin, kotlinx.coroutines, kotlinx.serialization-core (a transitive dependency of AndroidX SavedState) | Apache-2.0 |
| AndroidX | Apache-2.0 |
| OkHttp, Okio | Apache-2.0 |
| Kotpass 0.13.0 | MIT; includes Apache Commons / kotlin-xml-builder code (Apache-2.0) and Bouncy Castle cryptographic components under their permissive license |
| JUnit, MockWebServer | Test dependencies only; not included in the app |

Kotpass source and license: [keemobile/kotpass](https://github.com/keemobile/kotpass).
The local `.tools/` directory contains development tools and is excluded from both the app and Git.

The APK includes Kotpass, Apache, and Bouncy Castle license texts and notices in `assets/licenses/`.
