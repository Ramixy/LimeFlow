# LimeFlow

<p align="center">
  <img src="LimeFlow-GitHub-Banner.png" alt="LimeFlow" width="100%">
</p>

<p align="center">
  <b>Обход замедлений YouTube, Discord и Telegram на Android — без root.</b><br>
  Локальная обработка трафика через ByeDPI и встроенный Telegram WebSocket Proxy на Rust.
</p>

<p align="center">
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/Ramixy/LimeFlow?style=for-the-badge&label=%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C&color=1769E0" alt="Скачать"></a>
  <img src="https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 6.0+">
  <img src="https://img.shields.io/badge/root-%D0%BD%D0%B5%20%D0%BD%D1%83%D0%B6%D0%B5%D0%BD-43C99A?style=for-the-badge" alt="root не нужен">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Ramixy/LimeFlow?style=for-the-badge" alt="GPL-3.0"></a>
</p>

## Скачать

Последняя версия — на странице [Releases](../../releases/latest):

| Файл | Для каких телефонов |
|---|---|
| `LimeFlow-<версия>-arm64-v8a.apk` | почти все современные телефоны (64-бит) — **рекомендуется** |
| `LimeFlow-<версия>-armeabi-v7a.apk` | старые 32-битные устройства |
| `LimeFlow-<версия>-universal.apk` | если не знаете, какой выбрать |

Пакет `app.alt11.mobile`, минимальная версия Android — 6.0.

> **Обновление с 1.5.x.** Старые сборки были debug-APK с другой подписью. Перед установкой 1.6.0 один раз удалите прежнюю версию (настройки можно сохранить: «Настройки → Экспорт»). Дальше обновления ставятся поверх.

## Что умеет

**Обход DPI без root**
- работает через системный `VpnService` полностью на телефоне, без удалённого VPN-сервера;
- каталог стратегий: порты **альтов zapret-discord-youtube 1.10.4** (идут первыми), собственные цепочки LimeFlow, профили для операторов, Discord-голос;
- **автоподбор стратегии** — параллельная проверка YouTube, Discord, Google и Cloudflare в духе `test zapret.ps1`;
- **блокировка QUIC** (UDP 443): YouTube и браузеры сразу идут по TCP, где работает обход;
- свои стратегии: конструктор, импорт/экспорт и QR-коды;
- фильтр приложений (все / только выбранные / кроме выбранных) и список сайтов;
- запоминание стратегии для Wi-Fi и мобильной сети.

**Telegram WebSocket Proxy** (вкладка «Прокси»)
- локальный MTProto-прокси на Rust — порт [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) 1.11.4;
- Cloudflare-воркеры, прямой WebSocket к DC, мультиплексирование медиа по HTTP/2;
- **свой сервер в одну кнопку:** вводите IP, логин и пароль VPS — LimeFlow сам поставит nginx по SSH, домен `<ip>.sslip.io` не нужно покупать;
- маскировка SNI, разбивка TLS-рукопожатия и режим без TLS для сетей, где режут соединения с зарубежными серверами;
- проверка сервера по шагам (TCP → TLS → WebSocket).

**Удобство**
- виджеты 1×1 и 2×2, плитка в шторке, ярлыки и ссылки `limeflow://`;
- системная, светлая и тёмная темы, Material You и ручные палитры;
- статистика трафика, журнал для разработчика, экспорт настроек.

## Установка

1. Скачайте APK со страницы [Releases](../../releases/latest).
2. Разрешите установку из браузера или файлового менеджера.
3. При первом включении подтвердите запрос Android на VPN-подключение.

Значок VPN появляется, потому что трафик обрабатывается локально через системный VPN-интерфейс. Одновременно на Android может работать только один VPN — LimeFlow не совместим с другим включённым VPN-клиентом.

### Telegram через встроенный прокси

1. Вкладка **«Прокси»** → «Запустить».
2. Нажмите кнопку подключения — Telegram откроет ссылку `tg://proxy` и добавит прокси.
3. Telegram по умолчанию исключён из LimeFlow, чтобы движки не мешали друг другу.

## Сборка из исходников

Нужны JDK 17, Android SDK 34, NDK 25.1.8937393 и CMake 3.22.1.

```bash
git clone https://github.com/Ramixy/LimeFlow.git
cd LimeFlow
./gradlew testDebugUnitTest assembleDebug
```

Готовые APK — в `app/build/outputs/apk/debug/`. На Windows используйте `gradlew.bat`.

**Движок прокси** (`tgwsproxy/upstream-native`, Rust) лежит в репозитории уже собранным
(`tgwsproxy/src/main/jniLibs`). Чтобы пересобрать его, нужны Rust с целями
`aarch64-linux-android` и `armv7-linux-androideabi` и NDK:

```powershell
cd tgwsproxy/upstream-native
powershell -File build_android.ps1
```

**Подписанный релиз:** положите в корень `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; файл в `.gitignore`) и выполните
`./gradlew assembleRelease`.

## Структура

| Путь | Что внутри |
|---|---|
| `app/` | приложение LimeFlow (Kotlin), каталог стратегий, тестер |
| `app/src/main/cpp/byedpi/` | движок ByeDPI (C) с доработками LimeFlow |
| `app/src/main/jni/hev-socks5-tunnel/` | tun2socks (C) |
| `tgwsproxy/` | модуль Telegram WS Proxy (Kotlin + Compose) |
| `tgwsproxy/upstream-native/` | движок прокси на Rust |
| `tgwsproxy/src/main/assets/setup-vps.sh` | скрипт настройки своего сервера |

## Конфиденциальность

LimeFlow не содержит рекламы, аналитики и трекеров и не отправляет ваш трафик на свои серверы. Подробнее — в [PRIVACY.md](PRIVACY.md).

## Лицензия

LimeFlow распространяется по [GNU GPL v3](LICENSE). Сведения о сторонних компонентах и их лицензиях —
в [NOTICE.md](NOTICE.md) и [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

Проект основан на [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid),
[ByeDPI](https://github.com/hufrea/byedpi), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel),
[tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), [TG WS Proxy Android](https://github.com/amurcanov/tg-ws-proxy-android)
и [zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube). Эти проекты не связаны с LimeFlow.
