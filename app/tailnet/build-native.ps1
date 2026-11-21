#Requires -Version 7.0
# Builds app/app/src/main/jniLibs/<abi>/libdevingotailnet.so for arm64-v8a and
# x86_64 from pinned sources. Requires: git, go on PATH, Android NDK
# 28.2.13676358. Needs network on first run (Go toolchain + module download).
param(
    [string] $AndroidSdk = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$tailnet = (Resolve-Path $PSScriptRoot).Path          # app/tailnet
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$jniLibs = Join-Path $repoRoot 'app/app/src/main/jniLibs'
$build = Join-Path $tailnet 'build'
$src = Join-Path $build 'tailscale-v1.94.1'
$gomod = Join-Path $tailnet 'go'

if (!$AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$toolchain = Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin'
$clang = Join-Path $toolchain 'clang.exe'
if (!(Test-Path $clang)) { throw 'Install Android NDK 28.2.13676358 first.' }

$env:GOTOOLCHAIN = 'go1.25.5'
New-Item -ItemType Directory -Force $src | Out-Null
Push-Location $gomod
try {
    # Fresh pinned module source every run so the CGNAT patch applies cleanly.
    go mod edit '-dropreplace=tailscale.com'
    $module = go mod download -json 'tailscale.com@v1.94.1' | ConvertFrom-Json
    if ($LASTEXITCODE -or $module.Sum -ne 'h1:0dAst/ozTuFkgmxZULc3oNwR9+qPIt5ucvzH7kaM0Jw=') {
        throw 'Could not verify the pinned tailscale.com v1.94.1 module.'
    }
    Copy-Item (Join-Path $module.Dir '*') $src -Recurse -Force

    $patch = Join-Path $tailnet 'cgnat-netstack.patch'
    git -C $repoRoot apply --directory=app/tailnet/build/tailscale-v1.94.1 $patch
    if ($LASTEXITCODE) { throw 'Could not apply the CGNAT routing patch.' }
    if (!(Select-String -Path (Join-Path $src 'tsnet/tsnet.go') -Pattern 'CGNATRange' -Quiet)) {
        throw 'CGNAT routing patch did not take effect.'
    }

    go mod edit '-replace=tailscale.com=../build/tailscale-v1.94.1'
    go mod tidy
    if ($LASTEXITCODE) { throw 'go mod tidy failed.' }
    $env:GOFLAGS = '-mod=readonly'

    $env:GOOS = 'android'
    $env:CGO_ENABLED = '1'
    $env:CC = $clang
    $targets = @(
        @{ Abi = 'arm64-v8a'; GoArch = 'arm64'; Triple = 'aarch64-linux-android' }
        @{ Abi = 'x86_64'; GoArch = 'amd64'; Triple = 'x86_64-linux-android' }
    )
    $jniSymbols = @('start', 'status', 'forward', 'stop', 'logout')
    foreach ($target in $targets) {
        $env:GOARCH = $target.GoArch
        $env:CGO_CFLAGS = "--target=$($target.Triple)26"
        $env:CGO_LDFLAGS = "$env:CGO_CFLAGS -Wl,-z,max-page-size=16384 -Wl,-soname,libdevingotailnet.so"
        $outDir = Join-Path $jniLibs $target.Abi
        New-Item -ItemType Directory -Force $outDir | Out-Null
        $artifact = Join-Path $outDir 'libdevingotailnet.so'
        go build -buildmode=c-shared -trimpath '-buildvcs=false' -ldflags '-s -w' -o $artifact .
        if ($LASTEXITCODE) { throw "Could not build Android $($target.Abi) libdevingotailnet." }
        Remove-Item -Force ($artifact -replace '\.so$', '.h') -ErrorAction SilentlyContinue
        $symbols = & (Join-Path $toolchain 'llvm-nm.exe') -D --defined-only $artifact
        foreach ($sym in $jniSymbols) {
            if (!($symbols -match "T Java_com_devingo_app_tailnet_TailnetNative_$sym`$")) {
                throw "Android $($target.Abi) build lacks JNI symbol TailnetNative.$sym"
            }
        }
        Write-Host "[$($target.Abi)] all 5 JNI symbols exported."
        Get-FileHash -Algorithm SHA256 $artifact
    }
} finally {
    Pop-Location
}
