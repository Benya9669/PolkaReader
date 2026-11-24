# Установка, обновление и выпуск APK

## Для читателя

Polka Reader работает на Android 8.0 и новее. На телефоне или планшете откройте страницу последнего выпуска и скачайте из **Assets** файл `Polka-Reader-X.Y.Z.apk`. Откройте его и подтвердите установку. Если Android запросит разрешение на установку из этого источника, выдайте его браузеру или файловому менеджеру, которым открываете APK. [Последний выпуск Polka Reader](https://github.com/Benya9669/PolkaReader/releases/latest).

Для обновления откройте APK более новой версии на том же устройстве и выберите **Обновить**. Android сохраняет скачанные книги, сеанс и очередь синхронизации при обновлении приложения `ru.polka.reader` с прежней подписью. Если Android сообщает о конфликте пакетов, проверьте источник предыдущей сборки и отпечаток сертификата. У debug-сборки обычно другая подпись. Удаление приложения стирает его локальную библиотеку и неотправленные позиции.

Можно проверить APK перед установкой с компьютера. Скачайте APK и соседний файл `.apk.sha256` в одну папку. В примерах замените `0.1.1` на номер скачанного выпуска. Для проверки подписи добавьте каталог Android SDK Build Tools в `PATH`, для установки — Platform Tools. При нескольких подключённых устройствах используйте `adb -s СЕРИЙНЫЙ_НОМЕР install -r ...`.

**PowerShell**

```powershell
$apk = 'Polka-Reader-0.1.1.apk'
$expected = ((Get-Content -Raw -LiteralPath "$apk.sha256").Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $expected) { throw 'SHA-256 не совпадает' }
apksigner.bat verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'Подпись APK недействительна' }
adb install -r $apk
```

**Linux (Bash)**

```bash
apk='Polka-Reader-0.1.1.apk'
sha256sum --check "$apk.sha256" &&
  apksigner verify --verbose --print-certs "$apk" &&
  adb install -r "$apk"
```

Контрольная сумма подтверждает совпадение файла со скачанным описанием. Сравните `Signer #1 certificate SHA-256 digest` с отпечатком ранее проверенного выпуска или отпечатком, полученным от владельца по доверенному каналу. [Проверка APK через apksigner](https://developer.android.com/tools/apksigner).

### Установка через Obtainium

1. Установите Obtainium на Android. [Официальный сайт Obtainium](https://obtainium.imranr.dev/).
2. Откройте ссылку **Добавить в Obtainium** в README, проверьте источник `Benya9669/PolkaReader` и подтвердите импорт. Выберите установку **Polka Reader**, пакет `ru.polka.reader`. При запросе Android разрешите установку приложений из Obtainium. [Ссылка для добавления](../README.md#скачать-и-обновить).
3. Для ручного добавления в Obtainium откройте **Добавить приложение**, вставьте URL репозитория `https://github.com/Benya9669/PolkaReader` и выберите источник **GitHub**. Оставьте предварительные релизы и черновики выключенными. В поле фильтра APK по регулярному выражению вставьте шаблон ниже.

```text
^Polka-Reader-[0-9]+[.][0-9]+[.][0-9]+[.]apk$
```

Шаблон выбирает универсальный APK вида `Polka-Reader-0.1.0.apk`. Номер новой версии Obtainium узнаёт из тега GitHub Releases. При первом обновлении ранее установленной релизной сборки Android проверит подпись и сохранит локальные книги и настройки.

SHA-256 сертификата подписи публичной версии 0.1.0 — `4d5a7bf1bac088985e883503593f891f082f14dc7b4fbd12a5c7b636fab2cf34`. Его можно сверить в карточке установленного приложения в Obtainium. Контрольная сумма самого APK публикуется отдельно для каждого выпуска.

Те же параметры доступны для резервного способа добавления. Скачайте JSON и выберите его в разделе **Импорт/экспорт** Obtainium. [Скачать конфигурацию JSON](https://raw.githubusercontent.com/Benya9669/PolkaReader/main/docs/obtainium.json).

## Для владельца репозитория

### Версия и запуск выпуска

В корне хранится `VERSION` с одной строкой `X.Y.Z`. Gradle использует её как `versionName`. Код версии равен `X × 1 000 000 + Y × 1 000 + Z`. Например, `0.1.1` даёт `versionCode = 1001`. Допустимые диапазоны — `0…2099` для X и `0…999` для Y и Z. Каждая часть записывается десятичными цифрами без ведущих нулей, минимальная версия — `0.0.1`. Для следующего выпуска выберите версию выше последней опубликованной.

1. Сохраните новый номер в `VERSION` и включите изменение в коммит для `main`.
2. На pull request и при push в `main` GitHub Actions проверит проект и соберёт debug APK. Первый push или изменение `VERSION` в `main` также запустит выпуск после успешных проверок.
3. Откройте **Actions → Android CI / Release**. Для нового выпуска нужны четыре секрета подписи. Если один отсутствует, добавьте его и выберите **Run workflow → main → Run workflow**. Параметры вводить не требуется.
4. После успеха проверьте выпуск `vX.Y.Z` и оба вложения — `Polka-Reader-X.Y.Z.apk` и `Polka-Reader-X.Y.Z.apk.sha256`. Скачайте APK через страницу Releases и проверьте подпись и обновление на устройстве с предыдущей версией.

Повторный запуск для уже полностью опубликованного релиза пропускает публикацию. При сообщении о неполном опубликованном релизе проверьте его файлы и исходный коммит. Для повторной сборки черновика используйте запуск исходного коммита. Обычные изменения кода при неизменном `VERSION` на `main` проходят CI без создания релиза.

### Ключ и резервная копия

Для первого выпуска создайте постоянный ключ вне проекта на доверенной машине. Если у приложения уже есть пользователи подписанной версии, используйте её прежний ключ. Установите JDK c `keytool` и GitHub CLI, войдите через `gh auth login` под владельцем репозитория. Команды `keytool` и `gh secret set` запрашивают пароли интерактивно.

**PowerShell**

```powershell
$repo = 'Benya9669/PolkaReader'
$keyDir = Join-Path $env:USERPROFILE 'PolkaSigning'
New-Item -ItemType Directory -Force $keyDir | Out-Null
$store = Join-Path $keyDir 'polka-reader-release.jks'
if (Test-Path -LiteralPath $store) { throw 'Хранилище уже существует. Используйте имеющийся ключ.' }
$tool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $tool) { $tool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe' }
& $tool -genkeypair -keystore $store -storetype JKS -alias polka-reader -keyalg RSA -keysize 3072 -validity 10000
```

**Linux (Bash)**

```bash
repo='Benya9669/PolkaReader'
store="$HOME/.local/share/polka-signing/polka-reader-release.jks"
(
  set -eu
  umask 077
  mkdir -p "$(dirname "$store")"
  if [ -e "$store" ]; then echo 'Хранилище уже существует' >&2; exit 1; fi
  keytool -genkeypair -keystore "$store" -storetype JKS -alias polka-reader -keyalg RSA -keysize 3072 -validity 10000
)
```

Сохраните резервную копию JKS на отдельном защищённом носителе. В менеджере паролей сохраните имя записи `polka-reader`, пароль хранилища и пароль ключа. Если при запросе пароля ключа нажали Enter, для обоих секретов используйте пароль хранилища. Проверьте копию через `keytool -list -v -keystore ПУТЬ_К_КОПИИ -alias polka-reader` и сопоставьте SHA-256 сертификата. Потеря ключа препятствует установке следующей версии поверх уже установленной. [Правила подписи Android](https://developer.android.com/studio/publish/app-signing).

### Настройка GitHub Actions

В **Settings → Secrets and variables → Actions → Repository secrets** нужны следующие значения.

| Секрет | Содержимое |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Весь бинарный JKS в Base64, одной строкой |
| `ANDROID_KEYSTORE_PASSWORD` | Пароль JKS |
| `ANDROID_KEY_ALIAS` | Имя записи, в примере `polka-reader` |
| `ANDROID_KEY_PASSWORD` | Пароль записи ключа |

Следующие команды записывают секреты в выбранный репозиторий. Запустите их в терминале, где заданы `repo` и `store` из предыдущего примера. При восстановлении существующей подписи укажите путь к её JKS и прежний alias. Base64 передаётся по стандартному вводу напрямую в `gh`. Храните JKS, его Base64 и пароли вне Git, публичных артефактов и журналов. [Работа с секретами GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

**PowerShell**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes($store)) | gh secret set ANDROID_KEYSTORE_BASE64 --repo $repo
'polka-reader' | gh secret set ANDROID_KEY_ALIAS --repo $repo
gh secret set ANDROID_KEYSTORE_PASSWORD --repo $repo
gh secret set ANDROID_KEY_PASSWORD --repo $repo
gh secret list --repo $repo
```

**Linux (Bash)**

```bash
base64 -w0 < "$store" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo"
printf '%s' polka-reader | gh secret set ANDROID_KEY_ALIAS --repo "$repo"
gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo"
gh secret set ANDROID_KEY_PASSWORD --repo "$repo"
gh secret list --repo "$repo"
```

### Локальная проверка release-сборки

Для Gradle задайте абсолютный путь к хранилищу в `ANDROID_KEYSTORE_PATH`. Остальные три переменные совпадают с именами секретов в таблице. Запускайте команды из корня проекта. Результат находится в `app/build/outputs/apk/release/app-release.apk`.

**PowerShell**

```powershell
$env:ANDROID_KEYSTORE_PATH = $store
$env:ANDROID_KEY_ALIAS = 'polka-reader'
try {
    $env:ANDROID_KEYSTORE_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Пароль JKS' -AsSecureString)).Password
    $env:ANDROID_KEY_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Пароль ключа' -AsSecureString)).Password
    .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Сборка не завершилась' }
    apksigner.bat verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
} finally {
    Remove-Item Env:\ANDROID_KEYSTORE_PATH, Env:\ANDROID_KEY_ALIAS, Env:\ANDROID_KEYSTORE_PASSWORD, Env:\ANDROID_KEY_PASSWORD -ErrorAction SilentlyContinue
}
```

**Linux (Bash)**

```bash
(
  set -eu
  export ANDROID_KEYSTORE_PATH="$store" ANDROID_KEY_ALIAS=polka-reader
  read -rsp 'Пароль JKS: ' ANDROID_KEYSTORE_PASSWORD
  printf '\n'
  read -rsp 'Пароль ключа: ' ANDROID_KEY_PASSWORD
  printf '\n'
  export ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD
  ./gradlew :app:assembleRelease
  apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
)
```

Для проверки обновления установите прежний подписанный APK на отдельный эмулятор или тестовый телефон. Подключите его к серверу, скачайте книгу и сохраните место чтения. Соберите версию с большим `versionCode` и тем же ключом. Установите её через `adb install -r` и откройте ту же книгу без сети. Должны сохраниться аккаунт, локальная копия, позиция и ожидающие записи. После подключения проверьте синхронизацию на сервере.

Для принятой версии 0.1.0 этот сценарий воспроизведён на чистом эмуляторе Pixel 9a. В изолированной копии проекта собраны две версии — `0.1.0 (1000)` и `0.1.1 (1001)`. Проверка `apksigner` подтвердила одинаковый SHA-256 сертификата, `aapt2` — прежний идентификатор пакета. При обновлении в авиарежиме сохранились скачанный EPUB, вторая глава, шрифт 120 %, сепия и тёмная тема приложения. После подключения приложение отправило точный CFI сохранённым сеансом.

Чтобы повторить этот тест из версии 0.1.0, подготовьте локальный стенд по README, запустите отдельный эмулятор без установленного Polka Reader и задайте `JAVA_HOME`, `ANDROID_HOME` и `POLKA_QA_PASSWORD` для тестового стенда. Укажите фактический номер эмулятора и новую папку для сборки. Первый скрипт самостоятельно создаёт временный ключ и удаляет его после сборки двух APK. Второй переключает авиарежим эмулятора и использует аккаунт `readertwo`.

```powershell
.\.verification\venv\Scripts\python.exe tools/qa/upgrade_build.py .verification/upgrade-test
.\.verification\venv\Scripts\python.exe tools/qa/upgrade_flow.py .verification/upgrade-test/release-artifacts --serial emulator-5556
```

```bash
.verification/venv/bin/python tools/qa/upgrade_build.py .verification/upgrade-test
.verification/venv/bin/python tools/qa/upgrade_flow.py .verification/upgrade-test/release-artifacts --serial emulator-5556
```
