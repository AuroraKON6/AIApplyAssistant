Write-Host "Stopping all services..." -ForegroundColor Yellow

$ports = @(8001, 8002, 8888)
foreach ($port in $ports) {
    $conns = netstat -ano | Select-String ":$port\s.*LISTENING"
    foreach ($conn in $conns) {
        $pid = ($conn -split '\s+')[-1]
        if ($pid -match '^\d+$') {
            Write-Host "  Killing PID $pid (port $port)"
            Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "All services stopped." -ForegroundColor Green
Start-Sleep -Seconds 2
