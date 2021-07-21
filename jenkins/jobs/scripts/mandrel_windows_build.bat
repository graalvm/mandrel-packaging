@echo off

pushd %WORKSPACE%

set PYTHONHTTPSVERIFY=0
set "JAVA_HOME=C:\Program Files\%OPENJDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set MAVEN_REPO=C:\tmp\.m2\repository
set "MANDREL_REPO=%WORKSPACE%\mandrel"
set "MX_HOME=%WORKSPACE%\mx"

pushd mandrel
for /F "tokens=*" %%i in ('"git log --pretty=format:%%h -n1"') do set VER=%%i
for /f "tokens=6" %%g in ('java --version 2^>^&1 ^| findstr /R "Runtime.*build "') do set JAVA_VERSION=%%g
set JAVA_VERSION=%JAVA_VERSION:~0,-1%
popd

echo XXX JAVA_VERSION: %JAVA_VERSION%
echo XXX MX_HOME: %MX_HOME%
echo XXX MAVEN_REPO: %MAVEN_REPO%
echo XXX MANDREL_REPO: %MANDREL_REPO%
echo XXX JAVA_HOME: %JAVA_HOME%

call vcvars64
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

"%JAVA_HOME%\bin\java" -ea build.java --maven-local-repository "%MAVEN_REPO%" --mandrel-repo "%MANDREL_REPO%" --mx-home "%MX_HOME%" --archive-suffix zip --verbose
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

for /f "tokens=5" %%g in ('dir mandrel-*.zip ^| findstr /R mandrel-.*.zip') do set ZIP_NAME=%%g
powershell -c "$hash=(Get-FileHash %ZIP_NAME% -Algorithm SHA1).Hash;echo \"$hash %ZIP_NAME%\"">%ZIP_NAME%.sha1
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

powershell -c "$hash=(Get-FileHash %ZIP_NAME% -Algorithm SHA256).Hash;echo \"$hash %ZIP_NAME%\"">%ZIP_NAME%.sha256
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

for /f "delims=" %%a in ('dir /b /a:d mandrel-*') do @set MANDREL_HOME=%%a
echo XXX MANDREL_HOME: %MANDREL_HOME%
if not exist "%MANDREL_HOME%\bin\native-image.cmd" (
  echo "Cannot find native-image tool. Quitting..."
  exit 1
) else (
  echo "native-image.cmd is present, good."
)

for /f "tokens=2 delims= " %%a IN ( '%MANDREL_HOME%\bin\native-image --version' ) do set MANDREL_VERSION=%%a
echo XXX MANDREL_VERSION: %MANDREL_VERSION%
(
echo This is a dev build of Mandrel from https://github.com/graalvm/mandrel.
echo Mandrel %MANDREL_VERSION%
echo OpenJDK used: %JAVA_VERSION%
) >MANDREL.md

(
echo|set /p=" public class Hello {"
echo|set /p=" public static void main(String[] args) {"
echo|set /p="     System.out.println("Hello.");"
echo|set /p=" }"
echo|set /p=" }"
) >Hello.java

set "JAVA_HOME=%MANDREL_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
javac Hello.java
native-image Hello

for /F "tokens=*" %%i in ('hello.exe') do set HELLO_OUT=%%i
if "%HELLO_OUT%" == "Hello." (
  echo Done
) else (
  echo Native image fail
  exit 1
)
