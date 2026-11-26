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
    $dirty = @(& git status --porcelain --untracked-files=no)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Git status.' }
    $config = 'platforms/esp32/sdkconfig'
    # Only a plain unstaged modification of the generated config is allowed.
    # Staged changes, deletions, conflicts and every other file still block.
    $other = @($dirty | Where-Object { $_ -cne " M $config" })
    if ($other.Count -gt 0) { throw ("Tracked local changes: commit or save them first." + [Environment]::NewLine + ($other -join [Environment]::NewLine)) }
    if ($dirty.Count -gt 0) {
        $backupDir = Join-Path $env:LOCALAPPDATA 'MotoGPS\sdkconfig-backups'
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        $backup = Join-Path $backupDir ('sdkconfig-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N'))
        Copy-Item -LiteralPath $config -Destination $backup -ErrorAction Stop
        if ((Get-FileHash -LiteralPath $config).Hash -ne (Get-FileHash -LiteralPath $backup).Hash) { throw 'sdkconfig backup verification failed.' }
        Write-Host "Saved local sdkconfig: $backup"
        Invoke-Checked git @('restore','--worktree','--',$config)
        $remaining = @(& git status --porcelain --untracked-files=no)
        if ($LASTEXITCODE -ne 0 -or $remaining.Count -gt 0) { throw 'Checkout is not clean after sdkconfig backup.' }
    }
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

function Resolve-MotoAndroidSdk {
    # Preserve Android Studio's explicit local configuration, including custom paths.
    $properties = Join-Path (Get-Location).Path 'local.properties'
    $localSdk = $null
    if (Test-Path -LiteralPath $properties) {
        $entry = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^\s*sdk\.dir\s*[:=]' } | Select-Object -Last 1
        if ($entry) {
            $localSdk = ($entry -replace '^\s*sdk\.dir\s*[:=]\s*','').Trim()
            $localSdk = $localSdk.Replace('\:',':').Replace('\\','\').Replace('\ ',' ')
            if (-not [IO.Path]::IsPathRooted($localSdk)) {
                $localSdk = Join-Path (Get-Location).Path $localSdk
            }
            if (-not (Test-Path -LiteralPath (Join-Path $localSdk 'platforms') -PathType Container)) {
                throw "Invalid sdk.dir in $properties : $localSdk. Correct it in Android Studio SDK Manager or local.properties."
            }
        }
    }
    $candidates = @($localSdk, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    $sdk = $null
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'platforms') -PathType Container)) {
            $sdk = (Resolve-Path -LiteralPath $candidate).Path
            break
        }
    }
    if (-not $sdk) {
        throw 'Android SDK not found. Open Android Studio > Settings > Languages & Frameworks > Android SDK, install the SDK and set ANDROID_HOME to its location if nonstandard.'
    }
    # Session only: Gradle and ADB must use the same SDK. No persistent user settings changed.
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    Write-Host "Android SDK: $sdk"
    return $sdk
}

function Resolve-MotoJava {
    # Pin the local build to JDK 21: current Mockito/Byte Buddy cannot use Java 25.
    $candidates = @($env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'))
    $roots = @(
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:USERPROFILE '.jdks')
    )
    foreach ($root in $roots) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            $candidates += @(Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name -Descending | ForEach-Object { $_.FullName })
        }
    }
    foreach ($command in @(Get-Command java.exe -All -ErrorAction SilentlyContinue)) {
        if ($command.Source) { $candidates += (Split-Path (Split-Path $command.Source -Parent) -Parent) }
    }
    foreach ($candidate in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        $java = Join-Path $candidate 'bin\java.exe'
        $javac = Join-Path $candidate 'bin\javac.exe'
        $release = Join-Path $candidate 'release'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $javac -PathType Leaf) -or
            -not (Test-Path -LiteralPath $release -PathType Leaf)) { continue }
        $version = Get-Content -LiteralPath $release | Where-Object { $_ -match '^JAVA_VERSION="21(?:[.+-]|")' } | Select-Object -First 1
        if (-not $version) { continue }
        $jdk = (Resolve-Path -LiteralPath $candidate).Path
        $env:JAVA_HOME = $jdk
        Write-Host "Java: $version ($jdk)"
        return $jdk
    }
    throw 'JDK 21 not found. Install it with: winget install --id EclipseAdoptium.Temurin.21.JDK -e ; then reopen PowerShell or set JAVA_HOME to your JDK 21 directory.'
}

try {
    $jdk = Resolve-MotoJava
    Update-MotoRepository $Branch $PSScriptRoot
    Set-Location platforms/android
    $sdk = Resolve-MotoAndroidSdk
    Invoke-Checked '.\gradlew.bat' @("-Dorg.gradle.java.home=$jdk",'testDebugUnitTest','assembleDebug')
    if (-not $BuildOnly) {
        $adb = Join-Path $sdk 'platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) { throw 'ADB not found. Install Android SDK Platform-Tools in Android Studio SDK Manager.' }
        Invoke-Checked $adb @('devices')
        Invoke-Checked $adb @('-s',$Serial,'install','-r','.\app\build\outputs\apk\debug\app-debug.apk')
    }
} catch { Write-Error $_ -ErrorAction Continue; exit 1 }
