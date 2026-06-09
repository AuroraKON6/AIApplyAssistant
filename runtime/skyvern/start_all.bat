@echo off
chcp 65001 >nul 2>&1
title Skyvern + MiMo + get_jobs

set PYTHON=py -3.11
set SKYVERN_DIR=E:\github项目\AI操作浏览器\skyvern-main\skyvern-main
set GETJOBS_DIR=E:\新的投简历用的\get_jobs-main\get_jobs-main
set FRONT_DIR=%GETJOBS_DIR%\front

echo [1/4] Starting Proxy on port 8002...
netstat -ano | findstr ":8002 " >nul
if errorlevel 1 (
    start "Proxy-8002" /D "%SKYVERN_DIR%" py -3.11 local_proxy.py
    timeout /t 3 /nobreak >nul
) else (
    echo Proxy is already running.
)

echo [2/4] Starting Skyvern on port 8001...
netstat -ano | findstr ":8001 " >nul
if errorlevel 1 (
    start "Skyvern-8001" /D "%SKYVERN_DIR%" cmd /c "set ""PYTHONUTF8=1"" && py -3.11 -m uvicorn skyvern.forge.api_app:create_api_app --host 127.0.0.1 --port 8001 --factory"
    timeout /t 10 /nobreak >nul
) else (
    echo Skyvern is already running.
)

echo [3/4] Starting get_jobs backend on port 8888...
netstat -ano | findstr ":8888 " >nul
if errorlevel 1 (
    start "get_jobs-8888" /D "%GETJOBS_DIR%" cmd /c gradlew.bat bootRun
    timeout /t 15 /nobreak >nul
) else (
    echo get_jobs backend is already running.
)

echo [4/4] Starting frontend on port 6866...
netstat -ano | findstr ":6866 " >nul
if errorlevel 1 (
    start "front-6866" /D "%FRONT_DIR%" cmd /c npm run dev
    timeout /t 8 /nobreak >nul
) else (
    echo Frontend is already running.
)

echo Opening browser...
start http://127.0.0.1:6866/ai-apply

echo.
echo All services started!
echo   Proxy:    http://127.0.0.1:8002
echo   Browser:  Playwright Chromium
echo   Skyvern:  http://127.0.0.1:8001
echo   get_jobs: http://localhost:8888
echo   Frontend: http://127.0.0.1:6866/ai-apply
echo.
