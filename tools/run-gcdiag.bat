@echo off
setlocal
set GAME=D:\Steam\steamapps\common\SlayTheSpire
set OUT=C:\Users\YOKI\sts1-engine-solver\gc
set MTS=D:\Steam\steamapps\workshop\content\646570\1605060445\ModTheSpire.jar
set GC_OPTS=-Xmx10g -Xloggc:%OUT%\gc-%%p.log
if not "%~1"=="g1" goto keepgc
set GC_OPTS=%GC_OPTS% -XX:+UseG1GC -XX:MaxGCPauseMillis=150
:keepgc
if not exist "%GAME%\jre\bin\java.exe" goto nogame
if not exist "%MTS%" goto nomts
if not exist "%OUT%" mkdir "%OUT%"
cd /d "%GAME%"
echo Options: %GC_OPTS%
echo GC log goes to %OUT%\gc-<pid>.log
start "Slay the Spire (gc diag)" cmd /c ""%GAME%\jre\bin\java.exe" %GC_OPTS% -jar "%MTS%" --skip-launcher > "%OUT%\console.log" 2>&1"
exit /b 0
:nogame
echo Cannot find %GAME%\jre\bin\java.exe
pause
exit /b 1
:nomts
echo Cannot find %MTS%
pause
exit /b 1
