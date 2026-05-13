param(
    [int]$Port = 5179,
    [string]$Serial = "",
    [switch]$Install,
    [switch]$Launch,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$RendererDir = Join-Path $RepoRoot "bubble-renderer\src\main\assets\renderer"
$VersionFile = Join-Path $RendererDir "__version.txt"
$RuntimeDir = Join-Path $RepoRoot ".gradle"
$RuntimeFile = Join-Path $RuntimeDir "univcp-renderer-dev.json"
$PackageName = "com.univcp.android"

if ($Stop) {
    if (-not (Test-Path -LiteralPath $RuntimeFile)) {
        Write-Host "No renderer dev server runtime file found."
        exit 0
    }
    $runtime = Get-Content -LiteralPath $RuntimeFile -Raw | ConvertFrom-Json
    foreach ($pidToStop in @($runtime.serverPid, $runtime.scriptPid)) {
        if ($pidToStop) {
            $process = Get-Process -Id ([int]$pidToStop) -ErrorAction SilentlyContinue
            if ($process) {
                Stop-Process -Id $process.Id -Force
                Write-Host "Stopped process $($process.Id)."
            }
        }
    }
    Remove-Item -LiteralPath $RuntimeFile -Force -ErrorAction SilentlyContinue
    exit 0
}

if (-not (Test-Path -LiteralPath $RendererDir)) {
    throw "Renderer directory not found: $RendererDir"
}

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCommand) {
        throw "adb not found. Install Android platform-tools or add adb to PATH."
    }
    $adb = $adbCommand.Source
}

$deviceArgs = @()
if ($Serial) {
    $deviceArgs = @("-s", $Serial)
} else {
    $devices = & $adb devices | Select-String -Pattern "^\S+\s+device$"
    if ($devices.Count -eq 1) {
        $deviceArgs = @("-s", (($devices[0].Line -split "\s+")[0]))
    } elseif ($devices.Count -gt 1) {
        Write-Host "Multiple devices detected. Pass -Serial <device-id> if reverse uses the wrong one." -ForegroundColor Yellow
    }
}

function Update-RendererVersion {
    $stamp = [DateTimeOffset]::Now.ToUnixTimeMilliseconds().ToString()
    Set-Content -LiteralPath $VersionFile -Value $stamp -Encoding UTF8
    return $stamp
}

function Get-RendererSnapshot {
    Get-ChildItem -LiteralPath $RendererDir -Recurse -File |
        Where-Object { $_.Name -ne "__version.txt" } |
        Sort-Object FullName |
        ForEach-Object { "{0}|{1}|{2}" -f $_.FullName, $_.LastWriteTimeUtc.Ticks, $_.Length } |
        Out-String
}

$pythonCommand = Get-Command py -ErrorAction SilentlyContinue
$pythonArgs = @()
if ($pythonCommand) {
    $python = $pythonCommand.Source
    $pythonArgs = @("-3")
} else {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonCommand) {
        throw "Python not found. Install Python or add it to PATH to run the renderer dev server."
    }
    $python = $pythonCommand.Source
}

Push-Location $RepoRoot
try {
    if ($Install) {
        & (Join-Path $RepoRoot "gradlew.bat") ":app:installDebug" "--console=plain"
    }

    & $adb @deviceArgs reverse "tcp:$Port" "tcp:$Port" | Out-Host

    if ($Launch) {
        & $adb @deviceArgs shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Host
    }

    $initialVersion = Update-RendererVersion
    $serverArgs = $pythonArgs + @("-m", "http.server", "$Port", "--bind", "127.0.0.1", "--directory", $RendererDir)
    $server = Start-Process -FilePath $python -ArgumentList $serverArgs -PassThru -WindowStyle Hidden
    New-Item -ItemType Directory -Path $RuntimeDir -Force | Out-Null
    @{
        scriptPid = $PID
        serverPid = $server.Id
        port = $Port
        rendererDir = $RendererDir
        startedAt = (Get-Date).ToString("o")
    } | ConvertTo-Json | Set-Content -LiteralPath $RuntimeFile -Encoding UTF8

    Write-Host "Renderer dev server: http://127.0.0.1:$Port/renderer-shell.html"
    Write-Host "ADB reverse: tcp:$Port -> tcp:$Port"
    Write-Host "Initial renderer version: $initialVersion"
    Write-Host "Edit files under $RendererDir; the Android WebView reloads automatically. Press Ctrl+C to stop."

    $lastSnapshot = Get-RendererSnapshot
    while ($true) {
        Start-Sleep -Milliseconds 600
        $nextSnapshot = Get-RendererSnapshot
        if ($nextSnapshot -ne $lastSnapshot) {
            $lastSnapshot = $nextSnapshot
            $version = Update-RendererVersion
            Write-Host "Renderer changed -> $version"
        }
        if ($server.HasExited) {
            throw "Renderer HTTP server exited with code $($server.ExitCode)."
        }
    }
} finally {
    Pop-Location
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
    }
    Remove-Item -LiteralPath $RuntimeFile -Force -ErrorAction SilentlyContinue
}
