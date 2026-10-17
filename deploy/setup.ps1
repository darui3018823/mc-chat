#Requires -Version 7.0
# setup.ps1: Build and install mcchat-server on the VPS.
# Run on the Linux host from the repository, e.g. /root/projects/mcchat:
#
#   pwsh ./deploy/setup.ps1
#
# Safe to re-run: config.json and .env are only created when missing.

[CmdletBinding()]
param(
    # The name for the systemd service.
    [string]$ServiceName = "mcchat",

    # The public hostname served by nginx.
    [string]$Domain = "mcchat.daruks.com",

    # Skip installing the nginx site.
    [switch]$SkipNginx
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($IsWindows) {
    throw "This script is intended to run on the Linux host."
}

# The repository root (this script lives in ./deploy).
$AppDirectory = Split-Path -Parent $PSScriptRoot
$ExecPath = Join-Path $AppDirectory "mcchat-server"
$ConfigPath = Join-Path $AppDirectory "config.json"
$EnvPath = Join-Path $AppDirectory ".env"

function Invoke-Checked([string]$What, [scriptblock]$Block) {
    & $Block
    if ($LASTEXITCODE) { throw "$What failed (exit $LASTEXITCODE)" }
}

Write-Host "==> Building mcchat-server" -ForegroundColor Cyan
Push-Location (Join-Path $AppDirectory "server")
try {
    Invoke-Checked "go build" { go build -trimpath -o $ExecPath ./cmd/mcchat-server }
} finally {
    Pop-Location
}

if (-not (Test-Path $ConfigPath)) {
    Copy-Item (Join-Path $AppDirectory "server/config.example.json") $ConfigPath
    Write-Host "    Created $ConfigPath"
}

if (-not (Test-Path $EnvPath)) {
    $key = & $ExecPath keygen
    "MCCHAT_KEY=$key`nMCCHAT_KEY_ID=1`n" | Set-Content -Path $EnvPath -NoNewline
    chmod 600 $EnvPath
    Write-Host "    Generated $EnvPath -- BACK IT UP (stored messages are unreadable without it)" -ForegroundColor Yellow
}

Write-Host "==> Installing systemd service '$ServiceName'" -ForegroundColor Cyan
$ServiceFileContent = @"
[Unit]
Description=mcchat server ($ServiceName)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$AppDirectory
EnvironmentFile=$EnvPath
ExecStart=$ExecPath serve -config $ConfigPath
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
"@
$ServiceFileContent | Set-Content -Path "/etc/systemd/system/$ServiceName.service"
Invoke-Checked "daemon-reload" { systemctl daemon-reload }
Invoke-Checked "enable" { systemctl enable $ServiceName }
Invoke-Checked "restart" { systemctl restart $ServiceName }

if (-not $SkipNginx) {
    Write-Host "==> Installing nginx site '$Domain'" -ForegroundColor Cyan
    $site = "/etc/nginx/sites-available/$Domain"
    (Get-Content -Raw (Join-Path $PSScriptRoot "nginx/mcchat.daruks.com.conf")).Replace("mcchat.daruks.com", $Domain) |
        Set-Content -Path $site -NoNewline
    Invoke-Checked "ln" { ln -sf $site "/etc/nginx/sites-enabled/$Domain" }
    Invoke-Checked "nginx -t" { nginx -t }
    Invoke-Checked "nginx reload" { systemctl reload nginx }
}

Start-Sleep -Seconds 1
Write-Host "==> Health check" -ForegroundColor Cyan
$listen = (Get-Content -Raw $ConfigPath | ConvertFrom-Json).listen
if (-not $listen) { $listen = "127.0.0.1:25566" }
try {
    $r = Invoke-WebRequest -Uri "http://$listen/healthz" -TimeoutSec 5
    Write-Host "    $($r.StatusCode) $($r.Content.Trim())" -ForegroundColor Green
} catch {
    Write-Host "    health check failed: $_" -ForegroundColor Red
    systemctl --no-pager status $ServiceName
    exit 1
}

Write-Host ""
Write-Host "----------------- Next Steps -----------------" -ForegroundColor Yellow
Write-Host "Issue a token (run in $AppDirectory):"
Write-Host "   ./mcchat-server token add <name>"
Write-Host "Logs:"
Write-Host "   journalctl -u $ServiceName -f"
Write-Host "----------------------------------------------"
