param(
    [switch]$NoOpen
)

$ErrorActionPreference = "SilentlyContinue"

$AppDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SkyvernDir = Join-Path $AppDir "runtime\skyvern"
$FrontDir = Join-Path $AppDir "front"
$AppUrl = "http://127.0.0.1:6866/ai-apply"
$SkyvernEnvPath = Join-Path $SkyvernDir ".env"

function Test-Port {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Start-AppProcess {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory,
        [string]$OutLog,
        [string]$ErrLog
    )

    Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $OutLog `
        -RedirectStandardError $ErrLog
}

function Import-DotEnv {
    param([string]$Path)
    if (!(Test-Path $Path)) {
        return
    }

    Get-Content -Path $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (!$line -or $line.StartsWith("#") -or !$line.Contains("=")) {
            return
        }

        $parts = $line.Split("=", 2)
        $key = $parts[0].Trim()
        $value = $parts[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "Env:$key" -Value $value
    }
}

if (!(Test-Path (Join-Path $SkyvernDir "skyvern\config.py"))) {
    Add-Type -AssemblyName PresentationFramework
    [System.Windows.MessageBox]::Show(
        "Embedded Skyvern was not found.`n$SkyvernDir",
        "AI Apply Assistant",
        "OK",
        "Error"
    ) | Out-Null
    exit 1
}

Import-DotEnv $SkyvernEnvPath

$provider = ""
if ($env:GETJOBS_LLM_PROVIDER) {
    $provider = $env:GETJOBS_LLM_PROVIDER.ToLower()
}
$baseUrl = ""
if ($env:OPENAI_COMPATIBLE_API_BASE) {
    $baseUrl = $env:OPENAI_COMPATIBLE_API_BASE.ToLower()
}
$shouldStartMimo = $provider -eq "mimo" -or $baseUrl.Contains("127.0.0.1:8002") -or $baseUrl.Contains("localhost:8002")

if ($shouldStartMimo -and !(Test-Port 8002)) {
    Start-AppProcess `
        -FilePath "py" `
        -ArgumentList @("-3.11", "local_proxy.py") `
        -WorkingDirectory $SkyvernDir `
        -OutLog (Join-Path $SkyvernDir "mimo-proxy.log") `
        -ErrLog (Join-Path $SkyvernDir "mimo-proxy.err.log")
    Start-Sleep -Seconds 3
}

if (!(Test-Port 8001)) {
    Start-AppProcess `
        -FilePath "py" `
        -ArgumentList @("-3.11", "-X", "utf8", "-m", "uvicorn", "skyvern.forge.api_app:create_api_app", "--host", "127.0.0.1", "--port", "8001", "--factory") `
        -WorkingDirectory $SkyvernDir `
        -OutLog (Join-Path $SkyvernDir "skyvern-8001.log") `
        -ErrLog (Join-Path $SkyvernDir "skyvern-8001.err.log")
    Start-Sleep -Seconds 8
}

if (!(Test-Port 8888)) {
    $jarPath = Join-Path $AppDir "app.jar"
    if (!(Test-Path $jarPath)) {
        $jar = Get-ChildItem -Path (Join-Path $AppDir "build\libs") -Filter "*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike "*-plain.jar" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($jar) {
            $jarPath = $jar.FullName
        }
    }

    if (Test-Path $jarPath) {
        Start-AppProcess `
            -FilePath "java" `
            -ArgumentList @("-jar", $jarPath) `
            -WorkingDirectory $AppDir `
            -OutLog (Join-Path $AppDir "backend-8888.log") `
            -ErrLog (Join-Path $AppDir "backend-8888.err.log")
    } else {
        Start-AppProcess `
            -FilePath (Join-Path $AppDir "gradlew.bat") `
            -ArgumentList @("bootRun") `
            -WorkingDirectory $AppDir `
            -OutLog (Join-Path $AppDir "backend-8888.log") `
            -ErrLog (Join-Path $AppDir "backend-8888.err.log")
    }
    Start-Sleep -Seconds 8
}

if (!(Test-Port 6866)) {
    Start-AppProcess `
        -FilePath "cmd.exe" `
        -ArgumentList @("/c", "npm run dev") `
        -WorkingDirectory $FrontDir `
        -OutLog (Join-Path $FrontDir "front-dev.log") `
        -ErrLog (Join-Path $FrontDir "front-dev.err.log")
    Start-Sleep -Seconds 5
}

$Deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $Deadline -and !(Test-Port 6866)) {
    Start-Sleep -Seconds 1
}

if (!(Test-Port 6866) -and (Test-Port 8888)) {
    $AppUrl = "http://127.0.0.1:8888/ai-apply"
}

if (!$NoOpen) {
    Start-Process $AppUrl
}
