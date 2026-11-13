# Проверка Linux/F-Droid

Среда предназначена для локальной предварительной проверки: **Ubuntu-контейнер
с настоящими инструментами fdroidserver**, а не официальный buildserver VM.
Ключ подписи, пароли, GitHub Secrets и Docker socket внутрь не передаются.

## Что выполняется

1. Проверка SHA-256 опубликованного APK, сертификата и соответствия тега коммиту
   из публичного `release.json`.
2. Нативные `fdroid lint`, `fdroid scanner --refresh --exit-code` и
   `fdroid build --test --stop --scan-binary`. Scanner не отключается, force и
   исключения для обхода проверок не используются.
3. Независимая сборка исходников релизного тега и сравнение с GitHub APK через
   перенос подписи из `fdroidserver.apksigcopier` и проверку `apksigner`.
4. Сборка текущего закоммиченного HEAD, проектные проверки метаданных,
   JVM-тесты и Android Lint. Затем повторная компиляция без build cache с
   `--rerun-tasks` и сравнение неподписанных APK побайтово.

Текущий HEAD также проходит source-only scanner через отдельную временную
копию рецепта и локальный bundle. Основной рецепт и релизная сборка продолжают
использовать настоящий HTTPS-репозиторий; служебная копия не предназначена для
подачи в fdroiddata.

Дополнительно создаётся одноразовый тестовый ключ для проверки шага подписи с
`--alignment-preserved true`. Его APK используется только как тестовый образец,
не является релизом Keyra и не заменяет сравнение с настоящим опубликованным APK.
Перенос подписи проверяется также штатной функцией `fdroidserver.common.verify_apks`.

F-Droid build также самостоятельно выполняет сравнение с `Binaries` рецепта и
проверку `AllowedAPKSigningKeys`. Одинаковые распакованные файлы сами по себе не
доказывают воспроизводимость: требуется успешная проверка перенесённой подписи.
`compare-apks.py` сохраняет отдельный JSON с изменившимися ZIP entries для
диагностики; эти отличия не игнорируются ради успешного результата.

Текущая ветка может отличаться от релизного тега. Её APK сравнивается с повторной
сборкой этой же ветки, а не с APK другого коммита. Git bundle включает только
зафиксированные исходники. Изменения рабочего дерева в него не входят.

## Подготовка и запуск на Windows

Нужны Docker Desktop с Linux engine, Git, PowerShell и доступ к интернету.
Сборка образа загружает SDK и Python/Java-инструменты и может занять несколько
минут. Оставьте Docker не менее 6 GiB памяти и несколько GiB свободного диска.
Команды выполняются из корня Keyra.

После публикации релиза на JDK 21 подготовьте его публичные входные файлы
(пример для версии 0.1.2; тег и APK должны уже существовать):

```powershell
$ErrorActionPreference = 'Stop'
$keyraVersion = '0.1.2'
$keyraTag = 'v' + $keyraVersion
$checkDir = Join-Path $PWD.Path 'dist/fdroid-check'
New-Item -ItemType Directory -Force $checkDir | Out-Null
New-Item -ItemType Directory -Force (Join-Path $checkDir 'results') | Out-Null
git bundle create (Join-Path $checkDir 'keyra.bundle') HEAD "refs/tags/$keyraTag"
if ($LASTEXITCODE -ne 0) { throw 'Git bundle creation failed' }
$base = "https://github.com/PavelBelov/Keyra/releases/download/$keyraTag"
Invoke-WebRequest "$base/release.json" -OutFile (Join-Path $checkDir 'upstream-release.json')
Invoke-WebRequest "$base/SHA256SUMS" -OutFile (Join-Path $checkDir 'SHA256SUMS')
Invoke-WebRequest "$base/keyra-$keyraVersion.apk" -OutFile (Join-Path $checkDir "keyra-$keyraVersion.apk")
Copy-Item fdroid/metadata/xnet13.keyra.yml (Join-Path $checkDir 'xnet13.keyra.yml')
docker build --progress plain -t keyra-fdroid-check:local -f fdroid/verification/Dockerfile fdroid/verification
```

Запустите проверки, выбрав ещё не занятое имя контейнера:

```powershell
$keyraRoot = $PWD.Path.Replace('\', '/')
docker run --name keyra-fdroid-verification --cpus 4 --memory 6g `
  --mount "type=bind,source=$keyraRoot/dist/fdroid-check,target=/input,readonly" `
  --mount "type=bind,source=$keyraRoot/fdroid/verification,target=/checks,readonly" `
  --mount "type=bind,source=$keyraRoot/dist/fdroid-check/results,target=/output" `
  --mount 'type=volume,source=keyra-fdroid-work,target=/work' `
  keyra-fdroid-check:local bash /checks/run-checks.sh
```

`checks.tsv` содержит коды завершения всех этапов, отдельные `.log` — подробности.
Ненулевой итоговый код означает провал хотя бы одной проверки. Ошибка lint или
F-Droid build не мешает независимой Linux-сборке: это позволяет отдельно выявить
ошибку рецепта и ошибку воспроизводимости. Ошибка подготовительной проверки
входных данных останавливает запуск сразу.

Выходные данные лежат в `dist/fdroid-check/results/keyra-verify.*`.
`environment.txt` фиксирует JDK, Gradle, SDK и полный `pip freeze`.
`sources.txt` содержит оба проверенных коммита; `fdroiddata-catalog.json` —
ревизию и SHA-256 официальных каталогов с иконками. В частности, только копии
метаданных без `config/categories.yml` недостаточно для актуального `fdroid lint`.

Контейнер после завершения останавливается и сохраняется для диагностики.
Его файловая система содержит Gradle cache; workspace остаётся также в volume
`keyra-fdroid-work`. Они содержат публичные исходники и артефакты, а не ключи.
Для нового полного запуска используйте новое имя контейнера и обновите bundle.

## Версии и ограничения

- База Ubuntu 24.04 закреплена по digest в Dockerfile.
- Только JDK 21, включая уровень байткода Java/Kotlin;
  Gradle 8.11.1 с SHA-256 из wrapper проекта, Android Platform 36 и
  Build Tools 35.0.0; Command-line Tools 19.0 проверяется по checksum Google.
- fdroidserver 2.4.5; фактические версии его зависимостей записываются в отчёт.
- Каталоги fdroiddata закреплены на коммите
  `1efffc05164ce92b374ef44a64be111e70818617` в `run-checks.sh`.
- Обновления apt/Python-зависимостей не заморожены как архивный snapshot.
  Записывайте ID построенного образа (`docker image inspect IMAGE --format
  '{{.Id}}'`) вместе с результатами. При обновлении Gradle/SDK измените Dockerfile.
- `sudo` из рецепта пропускается инструментом при локальном `fdroid build`.
  В образе установлен и выбран JDK 21. Та же версия закреплена через
  `gradle/gradle-daemon-jvm.properties` и toolchain модулей.
  JDK 21 должен быть доступен и в официальной VM.
  Проверка команды в официальной VM
  и проверка CI fdroiddata остаются отдельными шагами.

Отчёт `RESULTS-2026-11-12.md` описывает прежнее окружение сборки. Для JDK 21
нужно заново выполнить Linux/F-Droid preflight и сравнение APK;
старые результаты не подтверждают воспроизводимость новой среды сборки.

При подписи Build Tools 35 и новее нужен `apksigner sign
--alignment-preserved true`. Без него инструмент переписывает выравнивание
локальных ZIP headers и ломает перенос подписи, даже если все файлы и сжатые
потоки совпали. Этот флаг включён в workflow Release. Проверка `zipalign -c`
перед подписью лишь проверяет исходный APK и не заменяет данный флаг.

Этот процесс ничего не публикует и не отправляет merge request в fdroiddata.
Успешный preflight не заменяет проверку и решение сопровождающих F-Droid.

Справка: [воспроизводимые сборки](https://f-droid.org/docs/Reproducible_Builds/),
[официальная buildserver VM](https://f-droid.org/docs/Build_Server_Setup/).
