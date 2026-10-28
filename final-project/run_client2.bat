@echo off
setlocal
cd /d "%~dp0assets"
echo Starting Super Smash Profs Game Client 2 (Isolated Profile)...
java -Duser.home="%TEMP%\ssprofs_player2" -Xms32m -Xmx192m -cp "..\desktop\build\libs\desktop-1.0.jar" com.smashprofs.game.DesktopLauncher --online
