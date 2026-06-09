$ErrorActionPreference = "Continue"

$SKYVERN_DIR = "E:\github项目\AI操作浏览器\skyvern-main\skyvern-main"
$GETJOBS_DIR = "E:\新的投简历用的\get_jobs-main\get_jobs-main"
$FRONT_DIR = Join-Path $GETJOBS_DIR "front"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Skyvern + MiMo + get_jobs" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Check Python launcher exists
if (-not (Test-Path "C:\Windows\py.exe")) {
    Write-Host "ERROR: Python launcher (py.exe) not found" -ForegroundColor Red
    pause
    exit 1
}

# 1. Proxy
Write-Host "[1/4] Starting Proxy on port 8002..." -ForegroundColor Yellow
if (-not (Get-NetTCPConnection -LocalPort 8002 -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath "cmd.exe" -ArgumentList "/k cd /d `"$SKYVERN_DIR`" && py -3.11 local_proxy.py"
    Start-Sleep -Seconds 3
} else {
    Write-Host "Proxy is already running." -ForegroundColor Gray
}

# 2. Skyvern
Write-Host "[2/4] Starting Skyvern on port 8001..." -ForegroundColor Yellow
if (-not (Get-NetTCPConnection -LocalPort 8001 -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath "cmd.exe" -ArgumentList "/k cd /d `"$SKYVERN_DIR`" && set `"PYTHONUTF8=1`" && py -3.11 -m uvicorn skyvern.forge.api_app:create_api_app --host 127.0.0.1 --port 8001 --factory"
    Start-Sleep -Seconds 12
} else {
    Write-Host "Skyvern is already running." -ForegroundColor Gray
}

# 3. get_jobs
Write-Host "[3/4] Starting get_jobs backend on port 8888..." -ForegroundColor Yellow
if (-not (Get-NetTCPConnection -LocalPort 8888 -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath "cmd.exe" -ArgumentList "/k cd /d `"$GETJOBS_DIR`" && gradlew.bat bootRun"
    Start-Sleep -Seconds 15
} else {
    Write-Host "get_jobs backend is already running." -ForegroundColor Gray
}

# 4. Frontend
Write-Host "[4/4] Starting frontend on port 6866..." -ForegroundColor Yellow
if (-not (Get-NetTCPConnection -LocalPort 6866 -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath "cmd.exe" -ArgumentList "/k cd /d `"$FRONT_DIR`" && npm run dev"
    Start-Sleep -Seconds 8
} else {
    Write-Host "Frontend is already running." -ForegroundColor Gray
}

# Open browser
Write-Host "Opening browser..." -ForegroundColor Yellow
Start-Process "http://127.0.0.1:6866/ai-apply"

Write-Host ""
Write-Host "All services started!" -ForegroundColor Green
Write-Host ""
