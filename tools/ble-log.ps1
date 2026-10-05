# 蓝牙现场取证：把你手机上「智味勺」的蓝牙链路日志抓下来
#
# 用法（PowerShell 5.1 / 7 都可以）：
#   .\tools\ble-log.ps1                 # 抓当前缓存里的日志
#   .\tools\ble-log.ps1 -Clear          # 先清空（推荐：清空 -> 手机上复现 -> 再跑一次不带 -Clear 的）
#   .\tools\ble-log.ps1 -Serial 1234abcd   # 指定设备（多台设备时用）
#   .\tools\ble-log.ps1 -Server 192.168.253.1:5037   # adb server 在宿主机上时
#
# 输出：终端打印 + 保存到 tools\ble-log-<时间>.txt
#
# 三个兼容性注意点（都踩过）：
# 1. 本文件必须存成 **UTF-8 带 BOM**：Windows PowerShell 5.1 对无 BOM 的 UTF-8
#    会按 GBK 解释，中文字符串会被截断成"字符串缺少终止符"，脚本直接跑不起来。
# 2. 多台设备连着时 `adb logcat` 会 **waiting for device** 挂住：
#    下面会自动挑出"装了智味勺的那台"，挑不出来就明确报错并让你用 -Serial。
# 3. 5.1 的控制台默认不是 UTF-8，脚本自己打印的中文会变乱码（不影响抓到的内容，
#    但看着难受），所以下面显式把 OutputEncoding 设成 UTF-8。

param(
    [switch]$Clear,
    [string]$Serial = "",
    [string]$Server = "",
    [string]$Adb = ""
)

$ErrorActionPreference = "Continue"

try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

function Find-Adb {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe") }
    $candidates += "C:\Android\Sdk\platform-tools\adb.exe"
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return ""
}

$adbPath = Find-Adb -Explicit $Adb
if (-not $adbPath) {
    Write-Host "找不到 adb.exe，请用 -Adb 指定路径，例如：" -ForegroundColor Red
    Write-Host '  .\tools\ble-log.ps1 -Adb "C:\Android\Sdk\platform-tools\adb.exe"'
    exit 1
}

if ($Server) { $env:ADB_SERVER_SOCKET = "tcp:$Server" }

Write-Host "== adb: $adbPath" -ForegroundColor Cyan
& $adbPath version 2>&1 | Select-Object -First 1 | Write-Host

Write-Host "`n== 连接的设备 ==" -ForegroundColor Cyan
$deviceLines = & $adbPath devices -l 2>&1
$deviceLines | Write-Host

$serials = @()
foreach ($line in $deviceLines) {
    if ($line -match '^(\S+)\s+device\b') { $serials += $Matches[1] }
    elseif ($line -match '^(\S+)\s+offline\b') { Write-Host "（$($Matches[1]) 处于 offline，已忽略）" -ForegroundColor Yellow }
}

if ($serials.Count -eq 0) {
    Write-Host "`n没有可用的设备（先确认手机已连上 / USB 调试已授权）。" -ForegroundColor Red
    exit 1
}

$target = @()
if ($Serial) {
    if ($serials -notcontains $Serial) {
        Write-Host "`n指定的设备 $Serial 不在列表里。当前可用：$($serials -join ', ')" -ForegroundColor Red
        exit 1
    }
    $target = @("-s", $Serial)
    Write-Host "`n使用指定设备：$Serial" -ForegroundColor Green
}
elseif ($serials.Count -eq 1) {
    $target = @("-s", $serials[0])
    Write-Host "`n使用唯一设备：$($serials[0])" -ForegroundColor Green
}
else {
    $spoon = @()
    foreach ($s in $serials) {
        $pkg = & $adbPath -s $s shell pm list packages com.equimeal.gramo 2>&1
        if ($pkg -match "com\.smartspoon\.l2") { $spoon += $s }
    }
    if ($spoon.Count -eq 1) {
        $target = @("-s", $spoon[0])
        Write-Host "`n多台设备，自动选中装了智味勺的那台：$($spoon[0])" -ForegroundColor Green
    }
    else {
        Write-Host "`n有多台设备，无法自动确定哪一台是智味勺（装了 App 的有 $($spoon.Count) 台）。" -ForegroundColor Red
        Write-Host "请用 -Serial 指定其中一台，例如：" -ForegroundColor Yellow
        foreach ($s in $serials) { Write-Host "  .\tools\ble-log.ps1 -Serial $s" }
        exit 1
    }
}

if ($Clear) {
    Write-Host "`n== 已清空日志；现在请在手机上复现（扫勺子 / 开始用餐 / 按 SW3）==" -ForegroundColor Yellow
    & $adbPath @target logcat -c
    exit 0
}

$out = Join-Path $PSScriptRoot ("ble-log-{0:yyyyMMdd-HHmmss}.txt" -f (Get-Date))
Write-Host "`n== 抓取日志（只看智味勺自己的链路日志 + 蓝牙栈错误）==" -ForegroundColor Cyan

# 只保留有意义的行：App 自己的链路日志（SpoonLink/BleSpoon/MealSession/BlePermission）
# 加上蓝牙栈的扫描与 GATT 错误；并把 WindowManager 那一堆窗口日志剔掉（否则刷屏看不到重点）。
$lines = & $adbPath @target logcat -d -v time 2>&1 |
    Select-String -Pattern "SpoonLink|BleSpoon|BlePermission|MealSession|SCAN_FAILED|bt_btm|btif|BluetoothGatt|BluetoothLeScanner" |
    Where-Object { $_ -notmatch "WindowManager|ActivityTaskManager|CoreBackPreview|MiuiFreeForm|DexOptExtImpl" }

$lines | ForEach-Object { $_.Line } | Tee-Object -FilePath $out | Write-Host

Write-Host "`n== 附加信息 ==" -ForegroundColor Cyan
$extra = @()
$extra += "--- 蓝牙开关（1=开） ---"
$extra += (& $adbPath @target shell settings get global bluetooth_on 2>&1)
$extra += "--- 定位开关（0=关 3=高精度） ---"
$extra += (& $adbPath @target shell settings get secure location_mode 2>&1)
$extra += "--- 适配器状态 ---"
$extra += (& $adbPath @target shell dumpsys bluetooth_manager 2>&1 |
    Select-String -Pattern "enabled:|state:|Number of Ble app registered" | Select-Object -First 5)
$extra += "--- App 蓝牙权限 ---"
$extra += (& $adbPath @target shell dumpsys package com.equimeal.gramo 2>&1 |
    Select-String -Pattern "BLUETOOTH_SCAN: granted|BLUETOOTH_CONNECT: granted|ACCESS_FINE_LOCATION: granted")
$extra += "--- App 版本 ---"
$extra += (& $adbPath @target shell dumpsys package com.equimeal.gramo 2>&1 |
    Select-String -Pattern "versionName=" | Select-Object -First 1)
$extra | Tee-Object -FilePath $out -Append | Write-Host

Write-Host "`n已保存到：$out" -ForegroundColor Green
Write-Host "把这个文件发给我，就能定位到具体是哪一步。" -ForegroundColor Green
