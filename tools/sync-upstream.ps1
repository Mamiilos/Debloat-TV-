param([switch]$Push)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot

function Invoke-GitChecked {
    param([string[]]$GitArguments)
    & git -c "safe.directory=$($repoRoot.Replace('\', '/'))" @GitArguments
    if ($LASTEXITCODE -ne 0) { throw "Git failed. Resolve the reported problem before continuing." }
}

$originUrl = & git -c "safe.directory=$($repoRoot.Replace('\', '/'))" remote get-url origin
if ($LASTEXITCODE -ne 0 -or $originUrl -notmatch '^https://github\.com/Deni7/free-my-tv(?:\.git)?$') {
    throw 'origin must point to the personal Deni7/free-my-tv fork.'
}
$upstreamUrl = & git -c "safe.directory=$($repoRoot.Replace('\', '/'))" remote get-url upstream
if ($LASTEXITCODE -ne 0 -or $upstreamUrl -notmatch '^https://github\.com/simbasjungles/free-my-tv(?:\.git)?$') {
    throw 'upstream must point to simbasjungles/free-my-tv.'
}
$changes = & git -c "safe.directory=$($repoRoot.Replace('\', '/'))" status --porcelain
if ($LASTEXITCODE -ne 0 -or $changes) { throw 'Commit or stash local changes before synchronizing.' }
$branch = & git -c "safe.directory=$($repoRoot.Replace('\', '/'))" branch --show-current
if ($LASTEXITCODE -ne 0 -or $branch -ne 'codex/mainfo-dashboard') {
    throw 'Run this command from the codex/mainfo-dashboard branch.'
}

Invoke-GitChecked -GitArguments @('fetch', 'upstream')
Invoke-GitChecked -GitArguments @('merge', '--no-edit', 'upstream/main')

$bashCommand = Get-Command bash.exe -ErrorAction SilentlyContinue
$gitBash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
if (Test-Path -LiteralPath $gitBash) { $bashPath = $gitBash }
elseif ($bashCommand) { $bashPath = $bashCommand.Source }
else { throw 'Git Bash is required to run tools/check.sh.' }

& $bashPath tools/check.sh
if ($LASTEXITCODE -ne 0) { throw 'Checks failed. The merged branch has not been pushed.' }
if ($Push) { Invoke-GitChecked -GitArguments @('push', 'origin', 'codex/mainfo-dashboard') }
Write-Host 'Upstream changes merged and checks passed.'
