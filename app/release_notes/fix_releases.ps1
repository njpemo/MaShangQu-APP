$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $root
# git credential fill needs input via stdin; use cmd pipe with temp file
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, "protocol=https`r`nhost=github.com`r`n`r`n")
$fill = cmd /c "git credential fill < `"$tmp`""
Remove-Item $tmp -Force
$tok = ($fill | Select-String "^password=").Line.Substring(9)
$headers = @{ Authorization = "Bearer $tok"; "User-Agent" = "njpemo" }
$repo = "njpemo/MaShangQu-APP"
$ids = @{ "v1.0.6" = 420828355; "v1.0.7" = 420977866; "v1.0.8" = 421381481 }
foreach ($tag in $ids.Keys) {
    $body = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot "$tag.md"), [System.Text.Encoding]::UTF8)
    $json = [System.Text.Encoding]::UTF8.GetBytes(('{"body":' + ($body | ConvertTo-Json) + '}'))
    Invoke-RestMethod -Method Patch -Uri "https://api.github.com/repos/$repo/releases/$($ids[$tag])" -Headers $headers -Body $json -ContentType "application/json; charset=utf-8" | Out-Null
    $back = (Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($ids[$tag])" -Headers $headers).body
    if ($back -eq $body) { Write-Output "$tag OK" } else { Write-Output "$tag MISMATCH"; Write-Output $back.Substring(0, 80) }
}
