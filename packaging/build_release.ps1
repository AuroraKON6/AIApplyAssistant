param(
    [string]$Version = "dev"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$DistRoot = Join-Path $Root "dist"
$ReleaseName = "AIApplyAssistant-$Version"
$ReleaseDir = Join-Path $DistRoot $ReleaseName
$ZipPath = Join-Path $DistRoot "$ReleaseName.zip"

function Copy-DirectoryClean {
    param(
        [string]$Source,
        [string]$Destination,
        [string[]]$ExcludeDirectoryNames = @(),
        [string[]]$ExcludeFileNames = @(),
        [string[]]$ExcludeExtensions = @()
    )

    if (!(Test-Path $Source)) {
        return
    }

    Get-ChildItem -Path $Source -Recurse -Force | ForEach-Object {
        $relative = $_.FullName.Substring($Source.Length).TrimStart("\", "/")
        if (!$relative) {
            return
        }

        $parts = $relative -split "[\\/]"
        if ($parts | Where-Object { $_ -in $ExcludeDirectoryNames }) {
            return
        }
        if (!$_.PSIsContainer) {
            if ($_.Name -in $ExcludeFileNames) {
                return
            }
            if ($_.Extension -in $ExcludeExtensions) {
                return
            }
        }

        $target = Join-Path $Destination $relative
        if ($_.PSIsContainer) {
            New-Item -ItemType Directory -Force -Path $target | Out-Null
        } else {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $target -Force
        }
    }
}

function Invoke-Checked {
    param(
        [scriptblock]$Command,
        [string]$Name
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

New-Item -ItemType Directory -Force -Path $DistRoot | Out-Null
if (Test-Path $ReleaseDir) {
    Remove-Item -LiteralPath $ReleaseDir -Recurse -Force
}
if (Test-Path $ZipPath) {
    Remove-Item -LiteralPath $ZipPath -Force
}
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

Push-Location (Join-Path $Root "front")
if ($IsWindows -or $env:OS -like "*Windows*") {
    Invoke-Checked { npm.cmd run build:prod } "Frontend build"
} else {
    Invoke-Checked { npm run build:prod } "Frontend build"
}
Pop-Location

Push-Location $Root
Invoke-Checked { .\gradlew.bat bootJar } "Backend bootJar"
Pop-Location

$jar = Get-ChildItem -Path (Join-Path $Root "build\libs") -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*-plain.jar" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (!$jar) {
    throw "Backend jar was not found."
}

Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $ReleaseDir "app.jar") -Force
Copy-Item -LiteralPath (Join-Path $Root "start_ai_apply.bat") -Destination $ReleaseDir -Force
Copy-Item -LiteralPath (Join-Path $Root "start_ai_apply.ps1") -Destination $ReleaseDir -Force
Copy-Item -LiteralPath (Join-Path $Root "README.md") -Destination $ReleaseDir -Force
Copy-Item -LiteralPath (Join-Path $Root "RELEASE_NOTES.md") -Destination $ReleaseDir -Force
Copy-Item -LiteralPath (Join-Path $Root "PRIVACY.md") -Destination $ReleaseDir -Force
Copy-Item -LiteralPath (Join-Path $Root "LICENSE") -Destination $ReleaseDir -Force

Copy-DirectoryClean `
    -Source (Join-Path $Root "runtime\skyvern") `
    -Destination (Join-Path $ReleaseDir "runtime\skyvern") `
    -ExcludeDirectoryNames @(".git", ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", "node_modules", "chrome-profile", "downloads", "har", "log", "logs", "temp", "video", "data", "artifacts", "browser_sessions") `
    -ExcludeFileNames @(".env") `
    -ExcludeExtensions @(".log", ".db", ".sqlite", ".pyc")

Copy-DirectoryClean `
    -Source (Join-Path $Root "src\main\resources\dist") `
    -Destination (Join-Path $ReleaseDir "src\main\resources\dist")

Compress-Archive -Path (Join-Path $ReleaseDir "*") -DestinationPath $ZipPath -Force

Write-Host "Release package created:"
Write-Host "  $ZipPath"
