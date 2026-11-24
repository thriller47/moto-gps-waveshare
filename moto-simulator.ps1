param(
    [Parameter(Mandatory=$true,Position=0)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._/-]*$')][string]$Branch,
    [switch]$BuildOnly,
    [string]$Distro = 'Ubuntu-24.04'
)
$ErrorActionPreference = 'Stop'
$mode = if ($BuildOnly) { 'build' } else { 'serve' }
$program = @'
set -euo pipefail
branch=$1
mode=$2
cd "$HOME/MotoGPS/moto-gps-waveshare"
mkdir -p "$HOME/motogps-build"
exec 9>"$HOME/motogps-build/.moto-motopack.lock"
flock -n 9 || { echo "MotoPack or simulator launcher already running. Stop it first." >&2; exit 1; }
if pgrep -f '(^|/|[[:space:]])(rebuild|build)_motopack_wsl[.]sh([[:space:]]|$)' >/dev/null; then
    echo "MotoPack generator already running; do not update this checkout." >&2
    exit 1
fi
if [[ "$mode" == serve ]]; then
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",4173)); s.close()' ||
        { echo "Port 4173 occupied. Stop the previous server first." >&2; exit 1; }
fi
git check-ref-format --branch "$branch"
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo "Tracked local changes; stop." >&2; exit 1; }
git fetch origin
git show-ref --verify --quiet "refs/remotes/origin/$branch"
git switch "$branch"
git merge --ff-only "origin/$branch"
[[ "$(git rev-parse HEAD)" == "$(git rev-parse "origin/$branch")" ]] || { echo "Local HEAD differs from origin." >&2; exit 1; }
git submodule update --init --recursive
git log -1 --oneline
test -f "$HOME/emsdk/emsdk_env.sh"
# emsdk setup may reference unset variables.
set +u
source "$HOME/emsdk/emsdk_env.sh"
set -u
command -v emcmake
if [[ -f platforms/web/simulator/synthetic_scenarios.test.cjs ]]; then
    if command -v node >/dev/null; then
        node --test platforms/web/simulator/synthetic_scenarios.test.cjs
    else
        echo "Node missing: synthetic fixture test skipped."
    fi
fi
bash scripts/build_web.sh
test -s platforms/web/shell/runtime/moto-gps-device.js
test -s platforms/web/shell/runtime/moto-gps-device.wasm
if [[ "$mode" == build ]]; then exit 0; fi
url=http://127.0.0.1:4173/platforms/web/simulator/index.html
python3 -m http.server 4173 --bind 127.0.0.1 --directory . &
server=$!
cleanup() { kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Only open the browser once our server is alive and answers HTTP.
python3 - "$server" "$url" <<'PY'
import os, sys, time, urllib.request
pid, url = int(sys.argv[1]), sys.argv[2]
for _ in range(40):
    os.kill(pid, 0)
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            if response.status == 200:
                break
    except OSError:
        time.sleep(.25)
else:
    raise SystemExit("Simulator HTTP server did not respond")
PY
kill -0 "$server"
echo "Simulator: $url (Ctrl+C to stop; Ctrl+F5 in browser)"
if ! powershell.exe -NoProfile -Command "Start-Process '$url'"; then
    echo "Open the URL above manually."
fi
wait "$server"
'@
$tmp = [IO.Path]::Combine($env:TEMP, ('moto-simulator-' + [Guid]::NewGuid().ToString('N') + '.sh'))
try {
    [IO.File]::WriteAllText($tmp, ($program -replace '\r\n', [string][char]10), (New-Object System.Text.UTF8Encoding($false)))
    $linux = & wsl.exe -d $Distro -- wslpath -a -u ($tmp.Replace('\','/'))
    if ($LASTEXITCODE -ne 0 -or -not $linux) { throw 'Cannot resolve temporary script in WSL.' }
    & wsl.exe -d $Distro -- bash $linux $Branch $mode
    $code = $LASTEXITCODE
    if ($code -eq 130) {
        Write-Host 'Simulator stopped (Ctrl+C).'
        $global:LASTEXITCODE = 0
    } elseif ($code -ne 0) {
        throw "Simulator exited ($code)."
    }
} finally {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}
