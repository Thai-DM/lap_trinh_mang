@echo off
setlocal
cd /d "%~dp0assets"
echo Starting Super Smash Profs Game Client...
java -Xms32m -Xmx192m -cp "..\desktop\build\libs\desktop-1.0.jar" com.smashprofs.game.DesktopLauncher --online
