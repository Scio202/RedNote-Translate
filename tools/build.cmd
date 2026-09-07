@echo off
set ROOT=%USERPROFILE%\android-toolchain
set JAVA_HOME=%ROOT%\jdk
set ANDROID_HOME=%ROOT%\sdk
set ANDROID_SDK_ROOT=%ROOT%\sdk
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "%~dp0.."
call "%ROOT%\gradle-8.9\bin\gradle.bat" %*
