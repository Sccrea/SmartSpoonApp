@echo off
chcp 65001 >nul
REM ============================================================================
REM  蓝牙现场取证（双击即可运行）
REM
REM  为什么有这个 .bat：Windows 上 .ps1 默认没有文件关联，双击会弹"如何打开"；
REM  而 .bat 双击一定能跑。它只做一件事：用正确的参数把同目录的 ps1 叫起来。
REM
REM  用法：
REM    1) 双击这个文件                → 先清空日志，然后去手机上复现，再双击一次
REM    2) 带参数（在 CMD 里）：ble-log.bat -Serial 1234abcd
REM                             ble-log.bat -Server 192.168.253.1:5037
REM
REM  注意：本文件**不能存成 UTF-8 带 BOM**（cmd.exe 会把 BOM 当成命令名，首行报错），
REM  所以它是 UTF-8 无 BOM + 上面的 chcp 65001。
REM ============================================================================
setlocal
set "HERE=%~dp0"
echo.
echo == 蓝牙现场取证 ==
echo.

REM 先探测 adb：优先环境变量，其次常见安装位置
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=C:\Android\Sdk\platform-tools\adb.exe"

if not exist "%ADB%" (
  echo [错误] 找不到 adb.exe。
  echo        请用参数指定，例如：
  echo        ble-log.bat -Adb "C:\path\to\adb.exe"
  echo.
  pause
  exit /b 1
)

echo 使用 adb: %ADB%
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%ble-log.ps1" -Adb "%ADB%" %*

echo.
pause
