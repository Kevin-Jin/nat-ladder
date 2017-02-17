@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION

IF NOT EXIST "%~dp0..\nat-ladder-common\bin" MKDIR "%~dp0..\nat-ladder-common\bin"
DEL /Q "%~dp0..\nat-ladder-common\bin\*.*"
FOR /D %%F IN ("%~dp0..\nat-ladder-common\bin\*.*") DO RMDIR /S /Q "%%F"
SET found=
FOR /R "%~dp0..\nat-ladder-common\src" %%F IN (*.java) DO SET found=!found! "%%F"
javac -d "%~dp0..\nat-ladder-common\bin" %found%

IF NOT EXIST "%~dp0..\nat-ladder-central\bin" MKDIR "%~dp0..\nat-ladder-central\bin"
DEL /Q "%~dp0..\nat-ladder-central\bin\*.*"
FOR /D %%F IN ("%~dp0..\nat-ladder-central\bin\*.*") DO RMDIR /S /Q "%%F"
SET found=
FOR /R "%~dp0..\nat-ladder-central\src" %%F IN (*.java) DO SET found=!found! "%%F"
javac -classpath "%~dp0..\nat-ladder-common\bin" -d "%~dp0..\nat-ladder-central\bin" %found%

IF NOT EXIST "%~dp0..\nat-ladder-client\bin" MKDIR "%~dp0..\nat-ladder-client\bin"
DEL /Q "%~dp0..\nat-ladder-client\bin\*.*"
FOR /D %%F IN ("%~dp0..\nat-ladder-client\bin\*.*") DO RMDIR /S /Q "%%F"
SET found=
FOR /R "%~dp0..\nat-ladder-client\src" %%F IN (*.java) DO SET found=!found! "%%F"
javac -classpath "%~dp0..\nat-ladder-common\bin" -d "%~dp0..\nat-ladder-client\bin" %found%

pause
