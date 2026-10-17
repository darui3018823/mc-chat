#Requires -Version 7
<#
.SYNOPSIS
    Build mcchat-server for Linux and install it on the VPS.

.EXAMPLE
    ./deploy/setup.ps1 -Target root@<vps-ip>

.EXAMPLE
    # Issue a token for a friend after deployment
    ./deploy/setup.ps1 -Target root@<vps-ip> -AddToken alice
#>
param(
    [Parameter(Mandatory)] [string] $Target,
    [string] $Key = "$HOME/.ssh/id_ed25519",
    [string] $AddToken
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$ssh = @('-i', $Key, '-o', 'BatchMode=yes')

if ($AddToken) {
    if ($AddToken -notmatch '^[\p{L}\p{N}_.-]{1,16}$') { throw "invalid name: $AddToken" }
    # Runs as the service user so the database stays owned by it.
    ssh @ssh $Target "runuser -u mcchat -- /opt/mcchat/mcchat-server token add -config /etc/mcchat/config.json '$AddToken'"
    exit $LASTEXITCODE
}

$stage = Join-Path ([IO.Path]::GetTempPath()) "mcchat-deploy-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory $stage | Out-Null
try {
    Push-Location (Join-Path $root 'server')
    try {
        $env:GOOS = 'linux'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
        go build -trimpath -ldflags '-s -w' -o (Join-Path $stage 'mcchat-server') ./cmd/mcchat-server
        if ($LASTEXITCODE) { throw 'go build failed' }
    } finally {
        Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
        Pop-Location
    }

    Copy-Item (Join-Path $root 'server/config.example.json') $stage
    Copy-Item (Join-Path $root 'deploy/systemd/mcchat.service') $stage
    Copy-Item (Join-Path $root 'deploy/nginx/mcchat.daruks.com.conf') $stage
    Copy-Item (Join-Path $root 'deploy/remote-install.sh') $stage

    $remote = "/tmp/mcchat-deploy-$([Guid]::NewGuid().ToString('N'))"
    ssh @ssh $Target "install -d -m 0700 $remote"
    if ($LASTEXITCODE) { throw 'ssh failed' }
    scp -i $Key -o BatchMode=yes -q (Get-ChildItem $stage).FullName "${Target}:$remote/"
    if ($LASTEXITCODE) { throw 'scp failed' }
    ssh @ssh $Target "bash $remote/remote-install.sh $remote"
    if ($LASTEXITCODE) { throw 'remote install failed' }
} finally {
    Remove-Item -Recurse -Force $stage
}
