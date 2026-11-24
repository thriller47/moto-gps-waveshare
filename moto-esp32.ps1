param(
    [Parameter(Mandatory=$true,Position=0)][string]$Branch,
    [string]$Port = 'COM3',
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
    if (-not $env:IDF_PATH) { throw 'Run moto first, then use the CMD launcher.' }
    Get-Command idf.py -ErrorAction Stop | Out-Null
    Update-MotoRepository $Branch $PSScriptRoot
    Set-Location platforms/esp32
    Invoke-Checked idf.py @('--version')
    $defaults = 'sdkconfig.defaults;sdkconfig.perf.defaults'
    if (-not (Test-Path sdkconfig.perf.defaults)) { throw 'Branch lacks performance defaults; use its documented build procedure.' }
    $required = @(Get-Content sdkconfig.perf.defaults | Where-Object { $_ -match '^CONFIG_[A-Z0-9_]+=y$' })
    if ($required.Count -eq 0) { throw 'Performance defaults are empty.' }
    if (Test-Path sdkconfig) {
        $lines = @(Get-Content sdkconfig)
        $changed = $false
        foreach ($setting in $required) {
            $key = $setting.Split('=')[0]
            if ($lines -cnotcontains $setting) {
                $escaped = [regex]::Escape($key)
                $lines = @($lines | Where-Object { $_ -notmatch "^($escaped=|# $escaped is not set$)" })
                $lines += $setting
                $changed = $true
            }
        }
        if ($changed) {
            $backup = 'sdkconfig.backup.' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
            Copy-Item sdkconfig $backup
            $lines | Set-Content sdkconfig -Encoding ASCII
            Write-Host "Saved $backup and enabled required runtime stats."
        }
    }
    Invoke-Checked idf.py @('-DSDKCONFIG_DEFAULTS='+$defaults,'reconfigure')
    $actual = @(Get-Content sdkconfig)
    foreach ($setting in $required) {
        if ($actual -cnotcontains $setting) { throw "Kconfig did not enable $setting; inspect sdkconfig before building." }
    }
    Invoke-Checked idf.py @('-DSDKCONFIG_DEFAULTS='+$defaults,'build')
    if (-not $BuildOnly) {
        Invoke-Checked idf.py @('-p',$Port,'flash','monitor')
    }
} catch { Write-Error $_ -ErrorAction Continue; exit 1 }
