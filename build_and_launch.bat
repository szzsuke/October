@echo off
set "JAVA_HOME=C:\Users\nishk\AppData\Local\Programs\jdk-21"
set "ANDROID_HOME=C:\Users\nishk\AppData\Local\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;C:\Users\nishk\AppData\Local\Android\platform-tools;%PATH%"

echo ========================================================
echo   OCTOBER: Real-Time Build and USB Direct Deploy
echo ========================================================
echo.

echo [1/3] Compiling October Debug build...
call gradlew.bat assembleGmsDebug -x lint -x test
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed with code %ERRORLEVEL%
    exit /b %ERRORLEVEL%
)

echo.
echo [2/3] Checking connected ADB device...
adb devices
for /f "tokens=1" %%d in ('adb get-state 2^>nul') do set "DEVICE_STATE=%%d"
if "%DEVICE_STATE%" neq "device" (
    echo [WARNING] Device not detected or unauthorized. Please check USB cable and tap 'Allow' on your phone.
    exit /b 1
)

echo.
echo [3/3] Installing APK onto your phone...
set "APK_FILE="
for /r app\build\outputs\apk\gms\debug %%f in (*.apk) do set "APK_FILE=%%f"

if not defined APK_FILE (
    echo [ERROR] No APK file found in app\build\outputs\apk\gms\debug
    exit /b 1
)

echo Found APK: %APK_FILE%
adb install -r -d "%APK_FILE%"
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Failed to install APK on device
    exit /b %ERRORLEVEL%
)

echo.
echo Launching October on your screen...
adb shell am start -n com.october.music.debug/com.metrolist.music.MainActivity
echo.
echo ========================================================
echo   DONE! October is running on your phone!
echo ========================================================
