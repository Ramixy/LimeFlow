# Third-party notices

LimeFlow is distributed under the **GNU General Public License v3.0**; the full
text is in [`LICENSE`](LICENSE). All components below are GPLv3-compatible. A
complete list of the Rust crates compiled into the Telegram proxy engine is in
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

## Application base

| Component | License | Source |
|---|---|---|
| ByeDPIAndroid | GPL-3.0 | https://github.com/dovecoteescapee/ByeDPIAndroid |
| TG WS Proxy Android (module `tgwsproxy`) | GPL-3.0 | https://github.com/amurcanov/tg-ws-proxy-android — license: `tgwsproxy/upstream-native/LICENSE` |

LimeFlow modifies both: new interface and design, strategy catalog and tester,
application routing, QUIC blocking, own-server installer, Rust engine ports of
Flowseal/tg-ws-proxy 1.11.x and stability work.

## Native engines

| Component | License | Source | License file |
|---|---|---|---|
| ByeDPI (C) | MIT, © 2024 hufrea | https://github.com/hufrea/byedpi | `app/src/main/cpp/byedpi/LICENSE` |
| hev-socks5-tunnel (C) | MIT, © 2022 hev | https://github.com/heiher/hev-socks5-tunnel | `app/src/main/jni/hev-socks5-tunnel/LICENSE` |
| tg-ws-proxy (protocol and logic, ported to Rust) | MIT, © Flowseal | https://github.com/Flowseal/tg-ws-proxy | — |

`hev-socks5-tunnel/third-part/` keeps the license files of its own bundled
components next to their sources.

## Strategy data

The fake TLS/QUIC/UDP payloads embedded in `FakePayloads.kt` and the host
lists used by the strategies are taken from **zapret-discord-youtube**
(MIT, © 2016-2026 bol-van; Flowseal), https://github.com/Flowseal/zapret-discord-youtube.
The DPI-bypass techniques follow **zapret** (MIT, bol-van), https://github.com/bol-van/zapret.
The strategy tester mirrors the approach of `utils/test zapret.ps1` from
zapret-discord-youtube 1.10.4.

## Android libraries

| Library | License |
|---|---|
| AndroidX (core, appcompat, fragment, lifecycle, preference, recyclerview, constraintlayout, datastore, activity, Compose) | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| PreferenceX (com.takisoft.preferencex) | Apache-2.0 |
| ZXing core | Apache-2.0 |
| JNA (Java Native Access) | Apache-2.0 OR LGPL-2.1 (used under Apache-2.0) |
| JSch (com.github.mwiede:jsch) | BSD-3-Clause |

None of these projects endorse LimeFlow. All trademarks belong to their
respective owners.
