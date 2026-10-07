# Builds libtgwsproxy.so for Android without cargo-ndk (works with the
# windows-gnu Rust host). Usage:  powershell -File build_android.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$props = Get-Content (Join-Path $root '..\..\local.properties') -ErrorAction SilentlyContinue
$ndk = ($props | Where-Object { $_ -like 'ndk.dir=*' }) -replace '^ndk.dir=', '' -replace '\\\\', '\' -replace '\\:', ':'
if (-not $ndk -or -not (Test-Path $ndk)) { $ndk = $env:ANDROID_NDK_HOME }
if (-not $ndk -or -not (Test-Path $ndk)) { throw "NDK not found (set ndk.dir in local.properties or ANDROID_NDK_HOME)" }
$bin = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'

$targets = @(
    @{ triple = 'aarch64-linux-android';   abi = 'arm64-v8a';   clang = 'aarch64-linux-android24-clang.cmd';    envName = 'AARCH64_LINUX_ANDROID' },
    @{ triple = 'armv7-linux-androideabi'; abi = 'armeabi-v7a'; clang = 'armv7a-linux-androideabi21-clang.cmd'; envName = 'ARMV7_LINUX_ANDROIDEABI' }
)

$jni = Join-Path $root '..\src\main\jniLibs'
foreach ($t in $targets) {
    $clang = Join-Path $bin $t.clang
    $clangExe = Join-Path $bin 'clang.exe'
    $clangTarget = $t.clang -replace '-clang\.cmd$', ''
    $lower = $t.triple -replace '-', '_'
    Set-Item "env:CARGO_TARGET_$($t.envName)_LINKER" $clangExe
    Set-Item "env:CARGO_TARGET_$($t.envName)_RUSTFLAGS" "-C link-arg=--target=$clangTarget"
    Set-Item "env:CC_$lower" $clang
    Set-Item "env:AR_$lower" (Join-Path $bin 'llvm-ar.exe')
    Write-Host "=== Building $($t.abi) ==="
    Push-Location $root
    try {
        cargo build --release --target $t.triple
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $($t.abi)" }
    } finally { Pop-Location }
    $out = Join-Path $root "target\$($t.triple)\release\libtgwsproxy.so"
    $dst = Join-Path $jni $t.abi
    New-Item -ItemType Directory -Force $dst | Out-Null
    Copy-Item $out (Join-Path $dst 'libtgwsproxy.so') -Force
    Write-Host ("{0}: {1} bytes" -f $t.abi, (Get-Item $out).Length)
}
