param(
    [Parameter(Mandatory=$true,Position=0)][string]$Branch,
    [string]$Serial = 'R5CXA3SF4QT',
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
function Invoke-Checked {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed ($LASTEXITCODE)" }
}
function Update-MotoRepository {
    param([string]$Branch, [string]$Root)
    Set-Location -LiteralPath $Root
    Invoke-Checked git @('check-ref-format','--branch',$Branch)
    $dirty = & git status --porcelain --untracked-files=no
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Tracked local changes: commit or save them first.' }
    Invoke-Checked git @('fetch','origin')
    & git show-ref --verify --quiet "refs/remotes/origin/$Branch"
    if ($LASTEXITCODE -ne 0) { throw "Remote branch not found: $Branch" }
    Invoke-Checked git @('switch',$Branch)
    Invoke-Checked git @('merge','--ff-only',"origin/$Branch")
    $head = & git rev-parse HEAD
    $remote = & git rev-parse "origin/$Branch"
    if ($LASTEXITCODE -ne 0 -or $head -ne $remote) { throw 'Local HEAD does not match remote; no build.' }
    Invoke-Checked git @('submodule','update','--init','--recursive')
    Invoke-Checked git @('log','-1','--oneline')
}

try {
    Update-MotoRepository $Branch $PSScriptRoot
    Set-Location platforms/android
    Invoke-Checked '.\gradlew.bat' @('testDebugUnitTest','assembleDebug')
    if (-not $BuildOnly) {
        $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) { throw 'ADB not found.' }
        Invoke-Checked $adb @('devices')
        Invoke-Checked $adb @('-s',$Serial,'install','-r','.\app\build\outputs\apk\debug\app-debug.apk')
    }
} catch { Write-Error $_ -ErrorAction Continue; exit 1 }
