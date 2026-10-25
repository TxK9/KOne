# Checks what the KOne MSI left on the runner: everything in place and the service running
# (-Version 1.0.<build>), or, after uninstalling (-Gone), nothing of it left at all.
param(
    [string] $Version,
    [switch] $Gone
)
$ErrorActionPreference = "Stop"
$problems = @()
$dir = Join-Path $env:ProgramFiles "KOne"
$files = @("KOne.exe", "core\kone-service.exe", "core\sing-box.exe", "core\rules\geosite-cn.srs", "core\rules\geoip-cn.srs", "core\rules\geosite-category-ads-all.srs")
$shortcuts = @((Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\KOne.lnk"), (Join-Path $env:PUBLIC "Desktop\KOne.lnk"))
$entries = @(Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -eq "KOne" })
$service = Get-CimInstance Win32_Service -Filter "Name='KOneService'"

if ($Gone) {
    if (Test-Path $dir) { $problems += "$dir is still there: $((Get-ChildItem -Recurse $dir | Select-Object -ExpandProperty FullName) -join ', ')" }
    if ($service) { $problems += "the KOne service is still registered" }
    if ($entries.Count -ne 0) { $problems += "KOne is still in Apps & features" }
    if (Test-Path "HKLM:\SOFTWARE\Classes\kone") { $problems += "kone:// is still registered" }
    foreach ($link in $shortcuts) { if (Test-Path $link) { $problems += "$link is still there" } }
    # Nothing of the connection outlives KOne: its adapter, or Windows' proxy pointed at it.
    if (Get-NetAdapter -Name "KOne" -ErrorAction SilentlyContinue) { $problems += "KOne's network adapter is still there" }
    $internet = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
    if ($internet.ProxyEnable -eq 1 -and "$($internet.ProxyServer)" -like "127.0.0.1:*") { $problems += "Windows' proxy still points at $($internet.ProxyServer)" }
} else {
    foreach ($file in $files) { if (-not (Test-Path (Join-Path $dir $file))) { $problems += "$file is missing" } }
    if ($entries.Count -ne 1) { $problems += "Apps & features lists KOne $($entries.Count) times" }
    elseif ($entries[0].DisplayVersion -ne $Version) { $problems += "Apps & features says $($entries[0].DisplayVersion), not $Version" }
    if (-not $service) { $problems += "the KOne service is not registered" }
    else {
        if ($service.State -ne "Running") { $problems += "the KOne service is $($service.State)" }
        if ($service.StartMode -ne "Auto") { $problems += "the KOne service starts $($service.StartMode)" }
        if ($service.StartName -ne "LocalSystem") { $problems += "the KOne service runs as $($service.StartName)" }
        $expected = "`"$(Join-Path $dir 'core\kone-service.exe')`" run"
        if ($service.PathName -ne $expected) { $problems += "the KOne service runs $($service.PathName), not $expected" }
        # Windows starts it again after any failure, not only the first two of a day.
        $restarts = @(sc.exe qfailure KOneService | Select-String "RESTART").Count
        if ($restarts -lt 3) { $problems += "the KOne service is restarted after $restarts failures, not every one" }
    }
    $open = (Get-ItemProperty "HKLM:\SOFTWARE\Classes\kone\shell\open\command" -ErrorAction SilentlyContinue).'(default)'
    if ($open -ne "`"$(Join-Path $dir 'KOne.exe')`" `"%1`"") { $problems += "kone:// opens '$open'" }
    foreach ($link in $shortcuts) { if (-not (Test-Path $link)) { $problems += "$link is missing" } }
    # Starting with Windows is the person's choice in KOne's settings, never the installer's.
    if ((Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -ErrorAction SilentlyContinue).KOne) { $problems += "KOne starts with Windows without being asked" }
    # Only administrators may change what the service runs.
    $acl = Get-Acl (Join-Path $dir "core")
    $writers = $acl.Access | Where-Object {
        $_.AccessControlType -eq "Allow" -and ($_.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::WriteData) -and
        $_.IdentityReference -notmatch "SYSTEM|Administrators|TrustedInstaller|CREATOR OWNER"
    }
    if ($writers) { $problems += "core\ is writable by $(($writers | Select-Object -ExpandProperty IdentityReference) -join ', ')" }
    & (Join-Path $dir "core\kone-service.exe") version
}

if ($problems) {
    $problems | ForEach-Object { Write-Host "::error::$_" }
    # throw, not exit: it stops the workflow step that called this script.
    throw "KOne is not $(if ($Gone) { 'gone' } else { 'installed' }) as it should be"
}
Write-Host ($(if ($Gone) { "KOne is gone without a trace" } else { "KOne $Version is installed as it should be" }))
