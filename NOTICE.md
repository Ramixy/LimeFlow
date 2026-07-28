# Third-party notices

LimeFlow is a modified Android application distributed under GNU GPL v3.
The repository retains the complete license text in `LICENSE`.

## Android application base

Parts of the Android application are derived from:

- **ByeDPIAndroid**, copyright its contributors, GNU GPL v3  
  https://github.com/dovecoteescapee/ByeDPIAndroid

Modifications include the LimeFlow interface, strategy catalog and tester,
application routing controls, persistence, traffic display, Android integration,
branding and additional stability work.

## Native components

- **ByeDPI**, copyright (c) 2024 hufrea, MIT License  
  https://github.com/hufrea/byedpi  
  License: `app/src/main/cpp/byedpi/LICENSE`

- **hev-socks5-tunnel**, copyright (c) 2022 hev, MIT License  
  https://github.com/heiher/hev-socks5-tunnel  
  License: `app/src/main/jni/hev-socks5-tunnel/LICENSE`

The tunnel source tree contains additional third-party components. Their license
files are preserved next to their source code under
`app/src/main/jni/hev-socks5-tunnel/third-part/`.

## Strategy research

The strategy catalog was adapted for the Android engine using public
configuration research from the DPI-circumvention ecosystem, including:

- https://github.com/Flowseal/zapret-discord-youtube
- https://github.com/bol-van/zapret

Those projects are not bundled as executables and do not endorse LimeFlow.

All trademarks belong to their respective owners.
