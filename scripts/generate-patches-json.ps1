param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$')]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [datetime]$CreatedAt = [datetime]::UtcNow
)

$metadata = [ordered]@{
    download_url = 'https://github.com/roflsunriz/vpass-revanced/releases/latest/download/vpass-patches.rvp'
    created_at = $CreatedAt.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss')
    signature_download_url = $null
    description = '三井住友カード Vpass アプリの Root・改ざん・USB デバッグ検知を無効化する ReVanced パッチ'
    version = $Version
}

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
