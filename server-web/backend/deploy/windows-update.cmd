@echo off
setlocal
chcp 65001 >nul
set "KONE_HOME=%LOCALAPPDATA%\KOneConsole"
set "KONE_PY=%LOCALAPPDATA%\Programs\Python\Python314\python.exe"
if not exist "%KONE_PY%" goto missing_python
if not exist "%KONE_HOME%\settings.json" goto missing_home
echo Preparing to update KOne. Data and settings will be retained.
powershell -NoProfile -Command "$ErrorActionPreference='Stop';$target=Join-Path $env:KONE_HOME 'app\deploy\run-center.py';Get-CimInstance Win32_Process | Where-Object {$_.Name -eq 'python.exe' -and $_.ExecutablePath -eq $env:KONE_PY -and $_.CommandLine -and $_.CommandLine.Contains($target)} | ForEach-Object {Stop-Process -Id $_.ProcessId -ErrorAction Stop}"
if errorlevel 1 goto failed
"%KONE_PY%" -X utf8 -B "%~dp0update-center.py" --home "%KONE_HOME%"
if errorlevel 1 goto failed
start "KOne Management Center" "%KONE_PY%" -X utf8 -B "%KONE_HOME%\app\deploy\run-center.py" --settings "%KONE_HOME%\settings.json"
start "" "http://127.0.0.1:8768/"
echo KOne updated. Please log in again.
pause
exit /b 0
:missing_python
echo The Python runtime used for the first Windows deployment is missing.
echo Use deploy/update-center.py with your installed Python after stopping KOne.
pause
exit /b 1
:missing_home
echo KOneConsole was not found for this Windows user. Nothing changed.
pause
exit /b 1
:failed
echo Update stopped. Check the message above. No database was overwritten.
echo If needed, restart the original center using start-windows.cmd.
pause
exit /b 1
