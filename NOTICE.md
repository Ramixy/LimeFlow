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

- **nfqws (zapret)**, copyright (c) 2016-2024 bol-van, MIT License  
  https://github.com/bol-van/zapret  
  Compiled from source for Android (arm64-v8a, armeabi-v7a) and bundled as
  `libnfqws.so`. Used by the opt-in root-only "Zapret Engine" mode. The MIT
  license text: https://github.com/bol-van/zapret/blob/main/docs/LICENSE.txt

  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in all
  copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  SOFTWARE.

## Bundled strategy data

The "Zapret Engine" mode ships host lists, fake packet binaries and strategy
configurations taken from **zapret-discord-youtube 1.10.0** (Flowseal):
https://github.com/Flowseal/zapret-discord-youtube

## Strategy research

The strategy catalog was adapted for the Android engine using public
configuration research from the DPI-circumvention ecosystem, including:

- https://github.com/Flowseal/zapret-discord-youtube
- https://github.com/bol-van/zapret

Those projects do not endorse LimeFlow.

All trademarks belong to their respective owners.
