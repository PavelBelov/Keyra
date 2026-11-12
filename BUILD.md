# Building Keyra

[English](BUILD.md) | [Русский](BUILD.ru.md)

## Required components

- JDK 17.
- Android SDK Platform — API 36 (`platforms;android-36`).
- Android SDK Build Tools — 35.0.0 (`build-tools;35.0.0`).
- Android SDK Platform-Tools — for installing the app and running tests on a device.

### JDK

To build from the terminal, set `JAVA_HOME` to the root directory of JDK 17,
not its `bin` directory. If this variable is not set, the Wrapper looks for Java in `PATH`.

<details>
<summary>Windows / PowerShell</summary>

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-17'
& "$env:JAVA_HOME\bin\java.exe" -version
```

</details>

<details>
<summary>Linux / macOS</summary>

```sh
export JAVA_HOME=/path/to/jdk-17
"$JAVA_HOME/bin/java" -version
```

</details>

In Android Studio, select the JDK for Gradle separately under:
**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**.
Choose JDK 17. When building from the IDE, this setting may differ from `JAVA_HOME`
in the terminal. See the [Android JDK documentation](https://developer.android.com/build/jdks) for details.

### Android SDK

Install the listed components using **SDK Manager** in Android Studio.
Enable **Show Package Details** to select a specific Build Tools version.
Accept the SDK licenses during installation.

Without Android Studio, you can use the Android SDK Command-Line Tools.
`sdkmanager` is located in the SDK's `cmdline-tools/<version>/bin` directory.
If `sdkmanager` is available in `PATH`, install the packages with:

```sh
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses
```

On Windows, use `sdkmanager.bat`. For instructions on installing the tools and packages,
see the [SDK Manager documentation](https://developer.android.com/tools/sdkmanager).

Set the SDK path using the `ANDROID_HOME` environment variable or a `local.properties`
file in the project root.

<details>
<summary>Windows</summary>

```properties
sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk
```

</details>

<details>
<summary>Linux / macOS</summary>

```properties
sdk.dir=/path/to/Android/Sdk
```

</details>

The Android Gradle Plugin automatically reads `local.properties` when building
from either the IDE or the terminal. If `sdk.dir` is set, that path is used for the SDK.

## Building

### Building and running checks from the terminal

<details>
<summary>Windows / PowerShell</summary>

```powershell
# Check that Gradle starts and which Java version it uses
.\gradlew.bat --version

# Run JVM tests and Android Lint, and build the debug APK
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

</details>

<details>
<summary>Linux / macOS</summary>

```sh
sh ./gradlew --version
sh ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

</details>

You can run tasks individually or pass several tasks in a single command:

| Task | Purpose |
| --- | --- |
| `:core:test` | Run JVM tests for the `core` module |
| `:app:testDebugUnitTest` | Run the app's JVM tests for the `debug` variant |
| `:app:lintDebug` | Run Android Lint static analysis |
| `:app:assembleDebug` | Build the `debug` APK |
| `:app:installDebug` | Build and install the `debug` APK on a device or emulator |
| `:app:connectedDebugAndroidTest` | Run instrumented tests on a device or emulator |
| `:app:assembleRelease` | Build the unsigned `release` APK |
| `:app:assemblePreview` | Build with `release` optimizations and sign with the debug key |
| `:app:assembleQa` | Build a variant based on `debug`, with `.qa` appended to the application ID and `-qa` to the version |
| `clean` | Remove module build outputs; the Gradle cache and reports in `fdroid/` are retained |

List the app's tasks: `:app:tasks --all`.

Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Lint report: `app/build/reports/lint-results-debug.html`.
- JVM test reports: `core/build/reports/tests/test/index.html`
  and `app/build/reports/tests/testDebugUnitTest/index.html`.

For more information on build and installation tasks,
see the [Android documentation](https://developer.android.com/build/building-cmdline).

### Building in Android Studio

1. Open the project's root directory.
2. Configure the JDK and SDK as described above, then run Gradle Sync.
3. In **Build Variants**, select `debug` for the `app` module.
4. Use the **Build** menu to build the app. To run it, select a device
   or emulator and click **Run**.

## Utilities and scripts

Like build tasks, the helper utilities are run through the Gradle Wrapper.

### exportDependencyLicenses

Resolves external release dependencies and calculates their SHA-256 hashes,
then reads licenses from POM files, including parent POMs, and updates
`fdroid/dependency-audit.json`.
This does not build an APK. The task covers external dependencies in
`releaseRuntimeClasspath` before R8 optimizations; test dependencies, build tools,
and third-party components included in the source tree are outside the report's scope.

Gradle retrieves POM files from the configured repositories and stores them
in its cache. If all dependencies have already been downloaded, you can add `--offline`.
An unknown or missing license causes the task to fail without overwriting
the previous license report. Apache-2.0 and MIT are currently allowed;
other licenses require a separate review and an update to the script's rules.

### validateFdroid

Checks the F-Droid recipe, its consistency with `app-info.properties`,
descriptions in both languages, icons, the presence of screenshots, and the dependency audit structure.
It does not build an APK or modify the project's source files or metadata.
It checks the saved `fdroid/dependency-audit.json`: the task does not recalculate
dependency hashes or compare the report against the current Gradle dependency graph.
After changing dependencies, run `exportDependencyLicenses` first.

These are project-specific Keyra checks, not a replacement for the official
`fdroid lint`, scanner, build, or F-Droid reproducibility checks.

### prepareRelease

Prepares the files for a new stable release:
updates `app-info.properties`, adds an entry to the F-Droid recipe, and creates
changelog templates.

```powershell
prepareRelease "-PreleaseVersion=1.2.3"

# Set versionCode explicitly if needed:
# prepareRelease "-PreleaseVersion=1.2.3" "-PreleaseCode=10"
```

- `releaseVersion` — the required version in `MAJOR.MINOR.PATCH` format, without `beta`/`rc`.
- `releaseCode` — the optional Android versionCode. For a new version, it must
  be greater than the current value; if omitted, the value is incremented by one.

Running the task again for the current version does not increment the code and preserves
existing changelogs. Decreasing the version is not allowed. The task does not create a commit or tag.
Run preparation separately from the APK build: Gradle reads the version when configuring
the modules, before executing tasks.

After preparation, fill in
`fastlane/metadata/android/{en-US,ru-RU}/changelogs/<versionCode>.txt`
and run `checkRelease`.

### checkRelease

Checks the version, versionCode, application ID,
the presence of the certificate fingerprint in the recipe, and the changelogs.
Each changelog must contain 1–500 characters without `TODO`, `TBD`, or `PLACEHOLDER` markers.
Run it together with `validateFdroid` to check the recipe's consistency and the remaining metadata.

Without additional parameters, the task does not modify the project's source files or metadata.
Available parameters:

| Gradle property | Purpose |
| --- | --- |
| `releaseTag` | Check that a local tag matches the version and points to the current commit |
| `releaseHistory` | Set to `true` to check that the version and code are greater than those of other stable tags; requires `releaseTag`; defaults to `false` |
| `releaseOutputDir` | Create or overwrite `release-notes.md` and `release.json` in the specified directory after successful checks |

Git must be available in `PATH` to check tags and generate release files.
All stable tags and their commits must be available to check the release history.
