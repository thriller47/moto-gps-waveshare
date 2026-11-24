param(
    [Parameter(Mandatory=$true,Position=0)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._/-]*$')][string]$Branch,
    [ValidateRange(1,64)][int]$Threads = 2,
    [switch]$Resume,
    [switch]$CopyOnly,
    [string]$Distro = 'Ubuntu-24.04'
)
$ErrorActionPreference = 'Stop'
if ($Resume -and $CopyOnly) { throw 'Choose Resume or CopyOnly, not both.' }
$mode = if ($Resume) { 'resume' } elseif ($CopyOnly) { 'copy' } else { 'fresh' }
$program = @'
set -euo pipefail
branch=$1
threads=$2
mode=$3
cd "$HOME/MotoGPS/moto-gps-waveshare"
mkdir -p "$HOME/motogps-build"
exec 9>"$HOME/motogps-build/.moto-motopack.lock"
flock -n 9 || { echo "Another launcher is running." >&2; exit 1; }
if pgrep -f '(^|/|[[:space:]])(rebuild|build)_motopack_wsl[.]sh([[:space:]]|$)' >/dev/null; then
    echo "MotoPack generator already running; stop here." >&2
    exit 1
fi
git check-ref-format --branch "$branch"
if [[ "$mode" == fresh ]]; then
    [[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo "Tracked local changes; stop." >&2; exit 1; }
    git fetch origin
    git show-ref --verify --quiet "refs/remotes/origin/$branch"
    git switch "$branch"
    git merge --ff-only "origin/$branch"
    [[ "$(git rev-parse HEAD)" == "$(git rev-parse "origin/$branch")" ]] || { echo "Local HEAD differs from origin." >&2; exit 1; }
else
    [[ "$(git branch --show-current)" == "$branch" ]] || { echo "Resume/copy requires original branch and checkout." >&2; exit 1; }
fi
git log -1 --oneline
source "$HOME/valhalla-env/bin/activate"
builder=scripts/offline_map/rebuild_motopack_wsl.sh
verifier=scripts/offline_map/motopack.py
[[ -f "$builder" && -f "$verifier" ]]
if grep -q 'elevation-italy.zip' "$builder"; then
    version=v3
else
    version=v2
fi
out="$HOME/motogps-build/ItaliaUnificata-RoadDNA-$version"
pack="$out/MotoChatGPS-Italia.motopack"
if [[ "$mode" != copy ]]; then
    args=(--out "$out" --threads "$threads")
    if [[ "$mode" == resume ]]; then args+=(--resume); fi
    bash "$builder" "${args[@]}"
fi
python3 "$verifier" verify "$pack"
dest=/mnt/c/MotoGPS/MotoChatGPS-Italia.motopack
[[ -d /mnt/c/MotoGPS ]]
tmp=$(mktemp /mnt/c/MotoGPS/.MotoChatGPS-Italia.motopack.XXXXXXXX)
trap 'rm -f -- "$tmp"' EXIT
cp -- "$pack" "$tmp"
cmp -- "$pack" "$tmp"
mv -fT -- "$tmp" "$dest"
printf 'Windows: C:\\MotoGPS\\MotoChatGPS-Italia.motopack\n'
# end of script
'@
# Use a temporary script on the Windows drive, with LF endings and no BOM.
$tmp = [IO.Path]::Combine($env:TEMP, ('moto-pack-' + [Guid]::NewGuid().ToString('N') + '.sh'))
try {
    [IO.File]::WriteAllText($tmp, ($program -replace '\r\n', [string][char]10), (New-Object System.Text.UTF8Encoding($false)))
    $linux = & wsl.exe -d $Distro -- wslpath -a -u ($tmp.Replace('\','/'))
    if ($LASTEXITCODE -ne 0 -or -not $linux) { throw 'Cannot resolve temporary script in WSL.' }
    & wsl.exe -d $Distro -- bash $linux $Branch $Threads $mode
    if ($LASTEXITCODE -ne 0) { throw "MotoPack failed ($LASTEXITCODE)." }
} finally {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}
