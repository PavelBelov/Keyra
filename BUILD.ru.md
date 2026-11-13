# Сборка Keyra

[English](BUILD.md) | [Русский](BUILD.ru.md)

## Необходимые компоненты

- JDK 21.
- Android SDK Platform — API 36 (`platforms;android-36`).
- Android SDK Build Tools — 35.0.0 (`build-tools;35.0.0`).
- Android SDK Platform-Tools — для установки приложения и тестов на устройстве.

### JDK

Для сборки из терминала укажите в `JAVA_HOME` путь к корневой папке JDK 21,
а не к её папке `bin`. Если переменная не задана, Wrapper ищет Java в `PATH`.

<details>
<summary>Windows / PowerShell</summary>

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -version
```

</details>

<details>
<summary>Linux / macOS</summary>

```sh
export JAVA_HOME=/path/to/jdk-21
"$JAVA_HOME/bin/java" -version
```

</details>

В Android Studio JDK для Gradle выбирается отдельно:
**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**.
Выберите JDK 21. При сборке из IDE этот выбор может отличаться от `JAVA_HOME` в терминале. Подробнее — в [документации Android о JDK](https://developer.android.com/build/jdks).

### Android SDK

Установите перечисленные компоненты через **SDK Manager** в Android Studio.
Для выбора конкретной версии Build Tools включите **Show Package Details**.
Примите лицензии SDK при установке.

Без Android Studio можно использовать Android SDK Command-Line Tools.
`sdkmanager` находится в папке SDK `cmdline-tools/<версия>/bin`.
Если `sdkmanager` доступен в `PATH`, команды установки такие:

```sh
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses
```

В Windows используйте `sdkmanager.bat`. Описание установки инструментов и пакетов —
в [документации SDK Manager](https://developer.android.com/tools/sdkmanager).

Путь к SDK задайте через переменную окружения `ANDROID_HOME` или через `local.properties` в корне проекта.

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

Android Gradle Plugin автоматически читает `local.properties` как при сборке из IDE, так и из терминала. При наличии `sdk.dir` SDK берётся по этому пути.

## Сборка

### Сборка и проверки из терминала

<details>
<summary>Windows / PowerShell</summary>

```powershell
# Проверить запуск Gradle и используемую Java
.\gradlew.bat --version

# Выполнить JVM-тесты, Android Lint и собрать debug APK
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

Можно запускать задачи по отдельности или передавать несколько задач одной командой:

| Задача                           | Назначение                                                                       |
| -------------------------------- | -------------------------------------------------------------------------------- |
| `:core:test`                     | JVM-тесты модуля `core`                                                          |
| `:app:testDebugUnitTest`         | JVM-тесты приложения для варианта `debug`                                        |
| `:app:lintDebug`                 | Статический анализ Android Lint                                                  |
| `:app:assembleDebug`             | Сборка APK варианта `debug`                                                      |
| `:app:installDebug`              | Сборка и установка APK варианта `debug` на устройство или эмулятор               |
| `:app:connectedDebugAndroidTest` | Инструментальные тесты на устройстве или эмуляторе                               |
| `:app:assembleRelease`           | Сборка неподписанного APK варианта `release`                                     |
| `:app:assemblePreview`           | Сборка с оптимизациями `release` и подписью отладочным ключом                    |
| `:app:assembleQa`                | Сборка на основе `debug` с суффиксом `.qa` в application ID и `-qa` в версии     |
| `clean`                          | Удаление результатов сборки модулей; кэш Gradle и отчёты в `fdroid/` сохраняются |

Список задач приложения: `:app:tasks --all`

Результаты:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Отчёт Lint: `app/build/reports/lint-results-debug.html`.
- Отчёты JVM-тестов: `core/build/reports/tests/test/index.html`
  и `app/build/reports/tests/testDebugUnitTest/index.html`.

Подробнее о задачах сборки и установки —
в [документации Android](https://developer.android.com/build/building-cmdline).

### Сборка в Android Studio

1. Откройте корневую папку проекта.
2. Настройте JDK и SDK, как описано выше, и выполните Gradle Sync.
3. В **Build Variants** выберите `debug` для модуля `app`.
4. Для сборки используйте меню **Build**, для запуска — выберите устройство
   или эмулятор и нажмите **Run**.

## Утилиты и скрипты

Аналогично сборке вспомогательные утилиты запускаются через Gradle Wrapper.

### exportDependencyLicenses

Получает внешние release-зависимости и вычисляет их SHA-256, затем читает
лицензии из POM-файлов, включая родительские, и обновляет
`fdroid/dependency-audit.json`.
APK при этом не собирается. Задача учитывает внешние зависимости
`releaseRuntimeClasspath` до оптимизаций R8; тестовые зависимости, инструменты
сборки и включённые в исходники сторонние компоненты в этот отчёт не входят.

POM-файлы Gradle получает из настроенных репозиториев и сохраняет
в своём кэше. Если все зависимости уже скачаны, можно добавить `--offline`.
При неизвестной или отсутствующей лицензии задача завершится ошибкой,
не перезаписывая прежний отчёт о лицензиях. Сейчас разрешены Apache-2.0 и MIT;
другие лицензии требуют отдельной проверки и изменения правил скрипта.

### validateFdroid

Задача проверяет рецепт F-Droid, его согласованность с `app-info.properties`,
описания на двух языках, иконки, наличие скриншотов и структуру отчёта о зависимостях.
Она не собирает APK и не изменяет исходники или метаданные проекта.
Проверяется сохранённый `fdroid/dependency-audit.json`: задача не пересчитывает
хеши зависимостей и не сверяет отчёт с текущим графом Gradle. После изменения
зависимостей сначала выполните `exportDependencyLicenses`.

Это проверки проекта Keyra, а не замена официальным `fdroid lint`, сканеру,
сборке и проверке воспроизводимости F-Droid.

### prepareRelease

Задача подготавливает файлы новой стабильной версии:
обновляет `app-info.properties`, добавляет запись в рецепт F-Droid и создаёт
шаблоны changelog.

```powershell
prepareRelease "-PreleaseVersion=1.2.3"

# При необходимости явно задайте versionCode:
# prepareRelease "-PreleaseVersion=1.2.3" "-PreleaseCode=10"
```

- `releaseVersion` — обязательная версия формата `MAJOR.MINOR.PATCH` без `beta`/`rc`.
- `releaseCode` — необязательный Android versionCode. Для новой версии он должен
  быть больше текущего; без этого параметра увеличивается на единицу.

Повторный запуск для текущей версии не увеличивает code и сохраняет существующие
changelog. Уменьшение версии запрещено. Задача не создаёт коммит или тег.
Подготовку запускайте отдельно от сборки APK: Gradle читает версию при настройке
модулей, до выполнения задач.

После подготовки заполните файлы
`fastlane/metadata/android/{en-US,ru-RU}/changelogs/<versionCode>.txt`
и запустите `checkRelease`.

### checkRelease

Задача проверяет версию, versionCode, application ID,
наличие отпечатка сертификата в рецепте и changelog. Каждый changelog должен
содержать от 1 до 500 символов без отметок `TODO`, `TBD` и `PLACEHOLDER`.
В описание релиза на GitHub попадает только английский changelog из `en-US/changelogs/<versionCode>.txt`.
Для проверки согласованности рецепта и остальных метаданных запускайте её
вместе с `validateFdroid`:

Без дополнительных параметров задача не меняет исходники или метаданные проекта.
Доступны параметры:

| Свойство Gradle    | Назначение                                                                                                             |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| `releaseTag`       | Проверить, что локальный тег совпадает с версией и указывает на текущий коммит                                         |
| `releaseHistory`   | `true` — проверить рост версии и code относительно других стабильных тегов; требует `releaseTag`; по умолчанию `false` |
| `releaseOutputDir` | Создать или перезаписать `release-notes.md` и `release.json` в указанной папке после успешных проверок                 |

Для проверки тегов и формирования релизных файлов нужен Git в `PATH`.
Для проверки истории должны быть доступны все стабильные теги и их коммиты.
