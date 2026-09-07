@echo off
set APP_HOME=%~dp0
if "%GRADLE_HOME%"=="" set GRADLE_HOME=%APP_HOME%\.gradle-home
call "%GRADLE_HOME%\bin\gradle.bat" %*
