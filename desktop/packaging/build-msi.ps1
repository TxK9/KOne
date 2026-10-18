# Builds the KOne MSI with WiX 3 (heat, candle, light), on Windows.
#
#   build-msi.ps1 -Wix <folder of heat.exe> -AppDir <Compose app image> -CoreDir <sing-box.exe,
#                 kone-service.exe, rules\> -ArtDir <dialog.bmp, banner.bmp> -Version 1.0.<build>
#                 -Out <KOne-1.0.<build>.msi> [-Arch x64|arm64] [-Site https://…]
param(
    [Parameter(Mandatory)] [string] $Wix,
    [Parameter(Mandatory)] [string] $AppDir,
    [Parameter(Mandatory)] [string] $CoreDir,
    [Parameter(Mandatory)] [string] $ArtDir,
    [Parameter(Mandatory)] [string] $Version,
    [Parameter(Mandatory)] [string] $Out,
    [ValidateSet("x64", "arm64")] [string] $Arch = "x64",
    [string] $Site = ""
)
$ErrorActionPreference = "Stop"

function Run([string] $Tool, [string[]] $Arguments) {
    & (Join-Path $Wix $Tool) @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed ($LASTEXITCODE)" }
}

$work = Join-Path ([System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($Out))) "wixobj-$Version"
New-Item -ItemType Directory -Force $work | Out-Null
$wxs = Join-Path $PSScriptRoot "wix"

# Everything in the app image except KOne.exe, which KOne.wxs installs with its shortcuts.
# Empty folders are left out (heat cannot give them a stable component ID, and nothing needs them).
Run "heat.exe" @("dir", $AppDir, "-nologo", "-cg", "AppFiles", "-dr", "INSTALLDIR", "-ag", "-sfrag", "-srd", "-sreg",
    "-var", "var.AppDir", "-t", (Join-Path $wxs "harvest.xslt"), "-out", (Join-Path $work "app.wxs"))

$defines = @("-dAppDir=$AppDir", "-dCoreDir=$CoreDir", "-dArtDir=$ArtDir", "-dPackaging=$PSScriptRoot", "-dVersion=$Version")
if ($Site) { $defines += "-dSite=$Site" }
Run "candle.exe" (@("-nologo", "-arch", $Arch, "-ext", "WixUIExtension", "-ext", "WixUtilExtension", "-out", "$work\") + $defines +
    @((Join-Path $wxs "KOne.wxs"), (Join-Path $work "app.wxs")))

Run "light.exe" @("-nologo", "-ext", "WixUIExtension", "-ext", "WixUtilExtension", "-cultures:en-US", "-loc", (Join-Path $wxs "KOne.zh-cn.wxl"),
    "-spdb", "-out", $Out, (Join-Path $work "KOne.wixobj"), (Join-Path $work "app.wixobj"))

Write-Host "MSI: $Out ($([math]::Round((Get-Item $Out).Length / 1MB, 1)) MB)"
