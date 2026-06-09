@echo off
chcp 65001 >nul
echo 正停用所有服务...

REM 按端口杀进程
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8001 "') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8002 "') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8888 "') do taskkill /F /PID %%a >nul 2>&1

echo 全部服务已停止。
pause
