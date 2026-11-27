# C盘空间概况
$disk = Get-WmiObject Win32_LogicalDisk -Filter 'DeviceID="C:"'
Write-Host '=== C盘空间概况 ==='
Write-Host ('总容量:    {0:N2} GB' -f ($disk.Size/1GB))
Write-Host ('已用:      {0:N2} GB' -f (($disk.Size - $disk.FreeSpace)/1GB))
Write-Host ('可用:      {0:N2} GB' -f ($disk.FreeSpace/1GB))
Write-Host ('使用率:    {0:N1}%' -f ((($disk.Size - $disk.FreeSpace)/$disk.Size)*100))
Write-Host ''

# 各区域扫描
$results = [ordered]@{}

# 系统临时文件
try { $results['系统临时文件 (Windows\Temp)'] = (Get-ChildItem 'C:\Windows\Temp' -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum } catch { $results['系统临时文件 (Windows\Temp)'] = 0 }

# 用户临时文件
try { $results['用户临时文件 (TEMP)'] = (Get-ChildItem $env:TEMP -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum } catch { $results['用户临时文件 (TEMP)'] = 0 }

# npm 缓存
$p = "$env:APPDATA\npm-cache"
if (Test-Path $p) { $results['npm 缓存'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# pnpm store
$p = "$env:LOCALAPPDATA\pnpm-store"
if (Test-Path $p) { $results['pnpm store'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# yarn 缓存
$p = "$env:LOCALAPPDATA\Yarn\Cache"
if (Test-Path $p) { $results['yarn 缓存'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# pip 缓存
$p = "$env:LOCALAPPDATA\pip\cache"
if (Test-Path $p) { $results['pip 缓存'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# Maven 仓库
$p = "$env:USERPROFILE\.m2\repository"
if (Test-Path $p) { $results['Maven 仓库'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# Gradle 缓存
$p = "$env:USERPROFILE\.gradle\caches"
if (Test-Path $p) { $results['Gradle 缓存'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# NuGet 缓存
$p = "$env:USERPROFILE\.nuget\packages"
if (Test-Path $p) { $results['NuGet 包缓存'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# Windows 更新缓存
try { $results['Windows 更新缓存'] = (Get-ChildItem 'C:\Windows\SoftwareDistribution\Download' -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum } catch { $results['Windows 更新缓存'] = 0 }

# Chrome 缓存
$chromeCache = 0
$cp1 = "$env:LOCALAPPDATA\Google\Chrome\User Data\Default\Cache"
$cp2 = "$env:LOCALAPPDATA\Google\Chrome\User Data\Default\Code Cache"
if (Test-Path $cp1) { $chromeCache += (Get-ChildItem $cp1 -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }
if (Test-Path $cp2) { $chromeCache += (Get-ChildItem $cp2 -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }
$results['Chrome 缓存'] = $chromeCache

# Edge 缓存
$edgeCache = 0
$ep1 = "$env:LOCALAPPDATA\Microsoft\Edge\User Data\Default\Cache"
$ep2 = "$env:LOCALAPPDATA\Microsoft\Edge\User Data\Default\Code Cache"
if (Test-Path $ep1) { $edgeCache += (Get-ChildItem $ep1 -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }
if (Test-Path $ep2) { $edgeCache += (Get-ChildItem $ep2 -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }
$results['Edge 缓存'] = $edgeCache

# 回收站
try { $results['回收站'] = (Get-ChildItem 'C:\$Recycle.Bin' -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum } catch { $results['回收站'] = 0 }

# CrashDumps
$p = "$env:LOCALAPPDATA\CrashDumps"
if (Test-Path $p) { $results['CrashDumps'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# Windows 错误报告
$p = "$env:LOCALAPPDATA\Microsoft\Windows\WER"
if (Test-Path $p) { $results['Windows 错误报告'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# Windows.old
if (Test-Path 'C:\Windows.old') { $results['Windows.old (旧系统)'] = (Get-ChildItem 'C:\Windows.old' -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

# Docker
$p = "$env:LOCALAPPDATA\Docker"
if (Test-Path $p) { $results['Docker 本地数据'] = (Get-ChildItem $p -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum }

Write-Host '=== 可清理区域详情 ==='
$total = 0
foreach ($k in $results.Keys) {
    $val = $results[$k]
    if ($val -gt 0) {
        $mb = [math]::Round($val / 1MB, 2)
        Write-Host ('{0,-35} {1,12:N2} MB' -f $k, $mb)
        $total += $val
    }
}
Write-Host ''
Write-Host ('以上合计:                          {0,12:N2} MB ({1:N2} GB)' -f ($total/1MB), ($total/1GB))
