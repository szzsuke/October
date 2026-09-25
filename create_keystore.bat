@echo off
if not exist "C:\Users\nishk\.android" mkdir "C:\Users\nishk\.android"
"C:\Users\nishk\AppData\Local\Programs\jdk-21\bin\keytool.exe" -genkey -v -keystore "C:\Users\nishk\.android\debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
echo Keystore created successfully:
dir "C:\Users\nishk\.android\debug.keystore"
