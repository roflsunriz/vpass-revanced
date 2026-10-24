param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$lines = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\CHANGELOG.md')
$start = ($lines | Select-String -Pattern "^## \[$([regex]::Escape($Version))\] - " | Select-Object -First 1).LineNumber
if (-not $start) {
    throw "CHANGELOG.md に版 $Version の見出しがありません"
}
$next = ($lines | Select-String -Pattern '^## \[' | Where-Object LineNumber -GT $start | Select-Object -First 1).LineNumber
$references = ($lines | Select-String -Pattern '^\[[^]]+\]:' | Where-Object LineNumber -GT $start | Select-Object -First 1).LineNumber
$boundary = @($next, $references) | Where-Object { $_ } | Sort-Object | Select-Object -First 1
$end = if ($boundary) { $boundary - 2 } else { $lines.Count - 1 }
$notes = $lines[($start - 1)..$end]

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$notes | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
