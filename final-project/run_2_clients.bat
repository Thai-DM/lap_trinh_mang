@echo off
title Launch 2 Game Clients
echo Dang mo Client 1 (Nguoi choi 1)...
start "Super Smash Profs - Client 1" run_client.bat
timeout /t 1 /nobreak >nul
echo Dang mo Client 2 (Nguoi choi 2 - Profile rieng)...
start "Super Smash Profs - Client 2" run_client2.bat
echo Da mo xong 2 client!
