$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
function Assert-MotoScriptSyntax {
    param([string]$Source, [string]$Name)
    $tokens = $null
    $errors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseInput($Source, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) {
        throw ("Invalid PowerShell syntax in " + $Name + ": " + (($errors | ForEach-Object { $_.Message }) -join '; '))
    }
}
# Validate all source scripts before replacing any installed launcher.
foreach ($name in @('moto-android.ps1','moto-esp32.ps1','moto-motopack.ps1','moto-simulator.ps1')) {
    Assert-MotoScriptSyntax (Get-Content -Raw -LiteralPath (Join-Path $root $name)) $name
}
# Install independent launchers outside the checkout so switching to a branch
# without the scripts does not remove the commands. Re-run after script updates.
$dir = Join-Path $env:LOCALAPPDATA 'MotoGPS\launchers'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
# The wrappers live outside Git but the build scripts use the real repo root.
# Copy the build scripts and replace their root argument with an explicit path.
foreach ($name in @('moto-android.ps1','moto-esp32.ps1')) {
    $source = Get-Content -Raw -LiteralPath (Join-Path $root $name)
    $needle = 'Update-MotoRepository $Branch $PSScriptRoot'
    if (-not $source.Contains($needle)) { throw "Unexpected script format: $name" }
    $literal = "'" + $root.Replace("'","''") + "'"
    $installedSource = $source.Replace($needle,('Update-MotoRepository $Branch ' + $literal))
    Assert-MotoScriptSyntax $installedSource $name
    $installedSource |
        Set-Content -LiteralPath (Join-Path $dir $name) -Encoding UTF8
}
foreach ($name in @('moto-esp32.cmd','moto-esp32-start.cmd','moto-motopack.ps1','moto-simulator.ps1')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination $dir -Force
}
$profilePath = $PROFILE.CurrentUserAllHosts
New-Item -ItemType Directory -Force -Path (Split-Path $profilePath) | Out-Null
$existing = ''
if (Test-Path -LiteralPath $profilePath) {
    $existing = [string](Get-Content -Raw -LiteralPath $profilePath)
    Copy-Item -LiteralPath $profilePath -Destination ($profilePath + '.moto-backup.' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
$start = '# BEGIN MOTO GPS BUILD COMMANDS'
$end = '# END MOTO GPS BUILD COMMANDS'
$block = @'
# BEGIN MOTO GPS BUILD COMMANDS
function moto-android {
    & (Join-Path $env:LOCALAPPDATA 'MotoGPS\launchers\moto-android.ps1') @args
}
function moto-esp32 {
    & (Join-Path $env:LOCALAPPDATA 'MotoGPS\launchers\moto-esp32-start.cmd') @args
}
function moto-motopack {
    & (Join-Path $env:LOCALAPPDATA 'MotoGPS\launchers\moto-motopack.ps1') @args
}
function moto-simulator {
    & (Join-Path $env:LOCALAPPDATA 'MotoGPS\launchers\moto-simulator.ps1') @args
}
# END MOTO GPS BUILD COMMANDS
'@
$pattern = '(?ms)^' + [regex]::Escape($start) + '\r?\n.*?^' + [regex]::Escape($end) + '[ \t]*\r?$'
if ($existing.Contains($start) -or $existing.Contains($end)) {
    if ([regex]::Matches($existing,$pattern).Count -ne 1) { throw 'Existing profile block is incomplete or duplicated. Inspect profile.' }
    $existing = [regex]::Replace($existing,$pattern,[System.Text.RegularExpressions.MatchEvaluator]{param($match) $block})
} else {
    $existing = $existing.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + $block
}
Set-Content -LiteralPath $profilePath -Value $existing -Encoding UTF8
Write-Host 'Installed. Open a new Windows PowerShell, then use moto-android NewUI or moto-esp32 NewUI or moto-motopack NewUI or moto-simulator NewUI.'
