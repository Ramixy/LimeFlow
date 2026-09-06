# LimeFlow

<p align="center">
  <img src="LimeFlow-GitHub-Banner.png" alt="LimeFlow" width="100%">
</p>

<p align="center">
  Локальная обработка Android-трафика без root с интегрированным Telegram WebSocket Proxy.
</p>

## Что нового в 1.4.0

Версия 1.4.0 добавляет второй движок и переносит приёмы zapret:

- **Zapret Engine (root)** — настоящий nfqws из [bol-van/zapret](https://github.com/bol-van/zapret) (MIT),
  собранный под Android и вшитый в APK. Работает системно через iptables + NFQUEUE без VPN-интерфейса.
  Стратегии — оригинальные конфиги [Flowseal 1.10.0](https://github.com/Flowseal/zapret-discord-youtube)
  один в один (General, ALT1–12, EXP, FAKE TLS AUTO, SIMPLE FAKE), со списками хостов и fake-бинарями;
- fake-пакеты zapret на основном движке: полные Chrome-ClientHello (google, max.ru, 4pda) и настоящий
  QUIC Initial встроены в стратегии ZF-серии, голосовые порты Discord получают отдельный UDP-декей;
- авто-TTL (`-z`): движок сам замеряет дистанцию до сервера UDP-пробой и подбирает TTL подмены;
- dupsid (`-Qd`) и новые якоря разреза `+sd` (midsld) и `+sx` (sniext);
- порт стратегий zapret 1.10.0 на byedpi (серия Z) и переключатель «Классический движок»;
- поиск стратегий переживает поворот экрана, тестирует стратегии на случайном свободном порте
  и показывает действительно лучшую стратегию.

Это локальный обход DPI, не шифрование Amnezia или WireGuard.
Полное описание находится в [CHANGELOG.md](CHANGELOG.md).

## Скачать

- [LimeFlow 1.4.0 Debug APK](releases/LimeFlow-1.4.0-debug.apk)
- [Раздел Releases](../../releases)

APK имеет пакет `app.alt11.mobile`, минимальная версия Android — 6.0.
Режим Zapret Engine дополнительно требует root-права.

## Основные возможности

- локальная обработка трафика через Android `VpnService`;
- отдельный встроенный раздел Telegram WebSocket Proxy;
- каталог встроенных стратегий и рекомендации для YouTube и Discord;
- создание, изменение, удаление, импорт и экспорт собственных стратегий;
- тестирование и сортировка стратегий;
- режимы обработки всех, выбранных или исключённых приложений;
- Telegram исключён из LimeFlow по умолчанию;
- системная, светлая и тёмная темы;
- Material You на Android 12 и новее;
- общие ручные палитры для LimeFlow и Proxy;
- импорт и экспорт настроек приложения;
- расширенные параметры HTTP, HTTPS, TLS, UDP, SNI, TTL и OOB.

## Установка

1. Скачайте APK из списка выше или из GitHub Releases.
2. Разрешите установку из браузера или файлового менеджера.
3. Установите приложение поверх предыдущей версии.
4. При первом включении LimeFlow подтвердите запрос Android на VPN-подключение.

LimeFlow не требует root. Значок VPN отображается, потому что обработка выполняется
локально через системный VPN-интерфейс.

## LimeFlow и Proxy

Нижняя общая панель переключает два раздела приложения:

- **LimeFlow** обрабатывает трафик выбранных приложений с помощью стратегий ByeDPI;
- **Proxy** запускает локальный Telegram WebSocket Proxy.

Чтобы движки не вмешивались в работу друг друга, Telegram добавлен в исключения
LimeFlow по умолчанию. Функциональная часть Proxy сохранена отдельно.

## Свои стратегии

На экране стратегий нажмите `+`, задайте название и настройте параметры конструктора.
Собственные профили можно изменять, удалять и проверять так же, как встроенные.

## Темы и палитры

По умолчанию включены системная тема и динамические цвета телефона. Выбор темы или
ручной палитры сохраняется одновременно для LimeFlow и Proxy, поэтому при переходе
между разделами фон и элементы интерфейса не меняются на несвязанное оформление.

## Сборка

Необходимы:

- JDK 17;
- Android SDK 34;
- Android NDK 25.1.8937393;
- CMake 3.22.1.

```bash
git clone --recurse-submodules https://github.com/ramixy/limeflow.git
cd limeflow
./gradlew assembleDebug
```

Готовый APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

На Windows используйте `gradlew.bat assembleDebug`.

## Проверки версии 1.1.9

- Kotlin и Android resources compilation;
- unit-тесты;
- Android Lint;
- сборка APK для `armeabi-v7a` и `arm64-v8a`;
- проверка подписи APK схемами v1 и v2.

## Конфиденциальность и лицензия

LimeFlow не содержит рекламы, аналитики или собственного удалённого VPN-сервера.
Подробнее: [PRIVACY.md](PRIVACY.md).

Проект распространяется по [GNU GPL v3](LICENSE). Сведения о сторонних компонентах
находятся в [NOTICE.md](NOTICE.md).
