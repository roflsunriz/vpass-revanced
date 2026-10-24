param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($resolved.Path)

try {
    $entries = @($archive.Entries | ForEach-Object FullName)
    $dexEntries = @($entries | Where-Object { $_ -match '^classes(?:\d+)?\.dex$' })
    if ($dexEntries.Count -eq 0) {
        throw "Android 用 DEX がありません。:patches:buildAndroid を実行してください: $Path"
    }
    if ('META-INF/MANIFEST.MF' -notin $entries) {
        throw "RVP manifest がありません: $Path"
    }
    Write-Output "Android RVP を確認しました: $($dexEntries -join ', ')"
} finally {
    $archive.Dispose()
}
