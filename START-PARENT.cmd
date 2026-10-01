@echo off
cd /d "%~dp0"
node parent-console/server.mjs
if errorlevel 1 pause
