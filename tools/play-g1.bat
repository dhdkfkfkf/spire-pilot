@echo off
rem Play Slay the Spire through ModTheSpire with G1 and a bigger heap.
rem Same mod list as the Steam launch (ModTheSpire reads its saved list),
rem but the JVM is started with -XX:+UseG1GC so the long whole-heap pauses go away.
rem Nothing in the game folder is modified; close this window after the game starts.
set GAME=D:\Steam\steamapps\common\SlayTheSpire
set MTS=D:\Steam\steamapps\workshop\content\646570\1605060445\ModTheSpire.jar
if not exist "%GAME%\jre\bin\java.exe" goto nojava
if not exist "%MTS%" goto nomts
rem the working directory must be the game folder, or saves/preferences land next to this file
cd /d "%GAME%"
start "" /D "%GAME%" "%GAME%\jre\bin\java.exe" -Xmx10g -XX:+UseG1GC -XX:MaxGCPauseMillis=150 -jar "%MTS%" --skip-launcher
exit /b 0
:nojava
echo Could not find "%GAME%\jre\bin\java.exe"
pause
exit /b 1
:nomts
echo Could not find "%MTS%"
pause
exit /b 1