<#
.SYNOPSIS
  Dump the Xiaomi Camera HAL from connected phones (Windows + adb).

.DESCRIPTION
  Collects the camera HAL libraries and provider binaries, odm camera
  plugins/dynamicplugins, vendor tuning, camera configs, the Camera APK and
  splits, props and dumpsys output into hal-dump\<codename>-<model>-<os>\.

  adb is taken from PATH when present. Otherwise the script downloads Google's
  official platform-tools into this folder (tools\windows\platform-tools) and
  uses that copy, so a full Android SDK install is not needed.

.PARAMETER Serial
  Dump only this adb serial. Default: every connected device.

.PARAMETER OutDir
  Output root. Default: .\hal-dump

.PARAMETER FullConfig
  Also pull /odm/etc/camera (models and tuning, several GB).

.PARAMETER SkipTuning
  Skip /vendor/lib64/camera and the odm plugin directories.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File dump-hal.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File dump-hal.ps1 -Serial 4ba100d6 -FullConfig
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$OutDir = ".\hal-dump",
    [switch]$FullConfig,
    [switch]$SkipTuning
)

$ErrorActionPreference = "Stop"
$ToolsRoot = $PSScriptRoot
$PlatformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

function Get-AdbPath {
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $pt = Join-Path $ToolsRoot "platform-tools"
    $adb = Join-Path $pt "adb.exe"
    if (Test-Path $adb) { return $adb }

    Write-Host "adb not found in PATH; downloading platform-tools..."
    $zip = Join-Path $ToolsRoot "platform-tools.zip"
    Invoke-WebRequest -UseBasicParsing -Uri $PlatformToolsUrl -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $ToolsRoot -Force
    Remove-Item $zip -Force
    if (-not (Test-Path $adb)) { throw "platform-tools download did not produce $adb" }
    return $adb
}

$Adb = Get-AdbPath
& $Adb start-server | Out-Null

function Get-Shell {
    param([string]$S, [string]$Command)
    ((& $Adb -s $S shell $Command 2>$null) | Out-String) -replace "`r", ""
}

function Get-Prop {
    param([string]$S, [string]$Name)
    (Get-Shell $S "getprop $Name").Trim()
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = @()
    foreach ($line in (& $Adb devices)) {
        if ($line -match '^(\S+)\s+device$') { $devices += $Matches[1] }
    }
    if ($devices.Count -eq 0) { throw "no adb device in 'device' state" }
} else {
    $devices = @($Serial)
}

$OutRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null

foreach ($S in $devices) {
    $codename = Get-Prop $S "ro.product.device"
    if ([string]::IsNullOrWhiteSpace($codename)) {
        Write-Host "== ${S}: not reachable, skipped"
        continue
    }
    $model = Get-Prop $S "ro.product.model"
    $market = Get-Prop $S "ro.product.marketname"
    $os = Get-Prop $S "ro.build.version.incremental"
    $android = Get-Prop $S "ro.build.version.release"
    $sdk = Get-Prop $S "ro.build.version.sdk"
    $fingerprint = Get-Prop $S "ro.build.fingerprint"
    $osSafe = if ($os) { $os -replace '[^A-Za-z0-9._-]', '_' } else { "unknown" }

    $out = Join-Path $OutRoot "$codename-$model-$osSafe"
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $out "apk") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $out "root") | Out-Null

    $root = $false
    $id = Get-Shell $S "su -c id"
    if ($id -match "uid=0") { $root = $true }
    Write-Host "== ${S}: $codename ($model), root=$(if ($root) {'yes'} else {'no'})"

    # Single quotes inside the command are for the device shell, so the host
    # command line carries no embedded double quotes (PowerShell 5.1 escaping).
    $find = "find /vendor/lib64 /vendor/lib /odm/lib64 /odm/lib /vendor/bin /odm/bin -maxdepth 4 \( -name 'camera*.so' -o -name 'com.qti.chi*.so' -o -name 'libmialgo*' -o -name 'libAnc*.so' -o -name 'libmiSAT*.so' -o -name 'libportrait*.so' -o -name 'libmiphone*.so' -o -name 'vendor.qti.camera*' -o -name 'android.hardware.camera.provider*' -o -name 'vendor.xiaomi.hardware.dynamiccameraserver' \) 2>/dev/null"
    $paths = @()
    foreach ($p in ((Get-Shell $S $find) -split "`n")) {
        if ($p.Trim()) { $paths += $p.Trim() }
    }
    $paths += "/vendor/etc/camera"
    $paths += "/odm/etc/camera/xiaomi"
    if (-not $SkipTuning) {
        $paths += "/vendor/lib64/camera"
        $paths += "/odm/lib64/camera/plugins"
        $paths += "/odm/lib64/camera/dynamicplugins"
    }
    if ($FullConfig) { $paths += "/odm/etc/camera" }

    $apkPaths = @()
    foreach ($line in ((Get-Shell $S "pm path com.android.camera") -split "`n")) {
        if ($line -match '^package:(.+)$') { $apkPaths += $Matches[1] }
    }
    $paths += $apkPaths

    $list = $paths | ForEach-Object { $_ -replace '^/', '' } | Sort-Object -Unique
    $listFile = Join-Path ([System.IO.Path]::GetTempPath()) ("hal-dump-list-" + [guid]::NewGuid() + ".txt")
    $remoteList = "/data/local/tmp/hal-dump-list.txt"
    $remoteTar = "/data/local/tmp/hal-dump.tar"
    Set-Content -Path $listFile -Value ($list -join "`n") -Encoding ASCII
    & $Adb -s $S push $listFile $remoteList | Out-Null

    $tarCmd = "tar -C / -cf $remoteTar -T $remoteList; chmod 644 $remoteTar"
    if ($root) {
        & $Adb -s $S shell "su -c '$tarCmd'" 2>$null | Out-Null
    } else {
        & $Adb -s $S shell $tarCmd 2>$null | Out-Null
    }

    $localTar = Join-Path $out "hal.tar"
    & $Adb -s $S pull $remoteTar $localTar 2>$null | Out-Null
    if (Test-Path $localTar) {
        & tar -xf $localTar -C (Join-Path $out "root") 2>$null
        Remove-Item $localTar -Force
    } else {
        Write-Host "   warning: could not stage the HAL files"
    }
    if ($root) {
        & $Adb -s $S shell "su -c 'rm -f $remoteTar $remoteList'" 2>$null | Out-Null
    } else {
        & $Adb -s $S shell "rm -f $remoteTar $remoteList" 2>$null | Out-Null
    }
    Remove-Item $listFile -Force -ErrorAction SilentlyContinue

    Get-Shell $S "getprop" | Set-Content -Path (Join-Path $out "props.txt")
    Get-Shell $S "dumpsys media.camera" | Set-Content -Path (Join-Path $out "dumpsys-media-camera.txt")
    $pkg = Get-Shell $S "dumpsys package com.android.camera"
    $pkg | Set-Content -Path (Join-Path $out "camera-package.txt")
    $camera = ""
    foreach ($line in ($pkg -split "`n")) {
        if ($line -match 'versionName=(\S+)') { $camera = $Matches[1]; break }
    }
    foreach ($p in $apkPaths) {
        & $Adb -s $S pull $p (Join-Path $out "apk") 2>$null | Out-Null
    }

    $deviceTxt = @(
        "model:       $model"
        "market:      $market"
        "device:      $codename"
        "os:          $os"
        "android:     $android (API $sdk)"
        "fingerprint: $fingerprint"
        "camera:      $camera"
        "root:        $(if ($root) {'yes'} else {'no'})"
        "serial:      $S"
        "dumped:      $(Get-Date -Format s)"
    )
    Set-Content -Path (Join-Path $out "DEVICE.txt") -Value $deviceTxt

    $manifest = @()
    $manifest += "device:      $codename ($model)"
    $manifest += "market:      $market"
    $manifest += "serial:      $S"
    $manifest += "os:          $os"
    $manifest += "android:     $android (API $sdk)"
    $manifest += "fingerprint: $fingerprint"
    $manifest += "camera:      $camera"
    $manifest += "root:        $(if ($root) {'yes'} else {'no'})"
    $manifest += ""
    $files = Get-ChildItem -Path $out -Recurse -File | Where-Object { $_.Name -ne "MANIFEST.txt" }
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($out.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -Path $f.FullName).Hash.ToLower()
        $manifest += ("{0,12}  {1}  {2}" -f $f.Length, $rel, $hash)
    }
    Set-Content -Path (Join-Path $out "MANIFEST.txt") -Value $manifest

    $size = "{0:N0} MB" -f ((Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
    Write-Host "   -> $out ($size)"
}

Write-Host "done: $OutRoot"
