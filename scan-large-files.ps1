# 扫描 C 盘大文件 (>200MB)，排除系统目录
Write-Host '=== C盘大文件 TOP 30 (>200MB, 排除Windows/ProgramFiles) ==='
Write-Host ''
$files = Get-ChildItem C:\ -Recurse -File -Force -ErrorAction SilentlyContinue |
    Where-Object { 
        $_.Length -gt 200MB -and 
        $_.FullName -notmatch '^C:\\Windows\\' -and 
        $_.FullName -notmatch '^C:\\Program Files' -and
        $_.FullName -notmatch '^C:\\Program Files \(x86\)'
    } |
    Sort-Object Length -Descending |
    Select-Object -First 30

foreach ($f in $files) {
    $mb = [math]::Round($f.Length / 1MB, 0)
    Write-Host ('{0,8} MB   {1}' -f $mb, $f.FullName)
}

# node_modules 残留
Write-Host ''
Write-Host '=== node_modules 残留目录 ==='
$nmDirs = Get-ChildItem C:\Users -Recurse -Directory -Force -ErrorAction SilentlyContinue | 
    Where-Object { $_.Name -eq 'node_modules' -and $_.FullName -notmatch 'node_modules\\.*\\node_modules' }
$nmTotal = 0
foreach ($d in $nmDirs) {
    $size = (Get-ChildItem $d.FullName -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
    $mb = [math]::Round($size / 1MB, 0)
    if ($mb -gt 100) {
        Write-Host ('{0,8} MB   {1}' -f $mb, $d.FullName)
        $nmTotal += $size
    }
}
Write-Host ''
Write-Host ('node_modules 合计 (>100MB): {0:N0} MB' -f ($nmTotal / 1MB))
