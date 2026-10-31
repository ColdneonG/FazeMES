@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title FazeMES - Reset business data and retain face and model settings
cd /d "%~dp0"
if errorlevel 1 goto directory_error

set "FAZEMES_PWSH="
for %%I in (pwsh.exe) do set "FAZEMES_PWSH=%%~$PATH:I"
if not defined FAZEMES_PWSH if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "FAZEMES_PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined FAZEMES_PWSH if exist "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe" set "FAZEMES_PWSH=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe"
if not defined FAZEMES_PWSH goto powershell_error

"%FAZEMES_PWSH%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\reinitialize-and-start.ps1" -KeepManualConfig %*
set "FAZEMES_EXIT=%ERRORLEVEL%"
echo.
if "%FAZEMES_EXIT%"=="0" (echo Command completed successfully.) else (echo FAILED. Read the error and the log path shown above.)
echo Press any key to close this window.
if not defined FAZEMES_LAUNCHER_NO_PAUSE pause >nul
exit /b %FAZEMES_EXIT%

:powershell_error
echo PowerShell 7 was not found. Install PowerShell 7 and try again.
goto failed
:directory_error
echo Cannot open the project directory. Keep this file inside the project folder.
:failed
echo Press any key to close this window.
if not defined FAZEMES_LAUNCHER_NO_PAUSE pause >nul
exit /b 1
