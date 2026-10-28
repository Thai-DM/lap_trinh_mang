@echo off
setlocal
cd /d "%~dp0"
title Super Smash Profs - Web Server and Gateway

echo ========================================================
echo    SUPER SMASH PROFS - KHOI DONG PHIEN BAN WEB (HTML5)
echo ========================================================
echo.

set "LIB_DIR=web\build\gateway\lib"
set "WEBAPP_DIR=web\build\dist\webapp"

if not exist "%LIB_DIR%\smashprofs-gateway.jar" (
    echo [LOI] Chua tim thay thu vien Gateway!
    pause
    exit /b 1
)

if not exist "%WEBAPP_DIR%\index.html" (
    echo [LOI] Chua tim thay thu muc webapp!
    pause
    exit /b 1
)

echo [1/3] Dang khoi dong WebSocket Gateway tren cong 8090...
start "SmashProfs Web Gateway (Port 8090)" java -Xmx192m -Dfile.encoding=UTF-8 -cp "%LIB_DIR%\*" com.smashprofs.web.GameGateway 8090

timeout /t 2 /nobreak >nul

echo [2/3] Dang khoi dong Web HTTP Server tren cong 8080...
start "SmashProfs Web HTTP Server (Port 8080)" python -m http.server 8080 --directory "%WEBAPP_DIR%"

timeout /t 1 /nobreak >nul

echo [3/3] Dang mo trinh duyet tai http://127.0.0.1:8080/ ...
start "" "http://127.0.0.1:8080/"

echo.
echo ========================================================
echo Web Gateway (8090) va HTTP Server (8080) dang chay!
echo De dung server, hay dong 2 cua so console vua duoc mo.
echo ========================================================
echo.
pause
