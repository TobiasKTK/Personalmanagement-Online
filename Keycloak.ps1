[CmdletBinding()]
param([switch]$Stoppen)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker mit Compose wird benoetigt. Docker Desktop installieren/starten und danach Keycloak.ps1 erneut ausfuehren.'
}
Push-Location -LiteralPath $PSScriptRoot
try {
    & docker compose version
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose ist nicht verfuegbar.' }
    $envDatei = Join-Path $PSScriptRoot '.env'
    $vorhanden = if (Test-Path -LiteralPath $envDatei) { [System.IO.File]::ReadAllText($envDatei) } else { '' }
    $fehlend = @('PEM_KEYCLOAK_ADMIN_PASSWORD', 'PEM_KEYCLOAK_APP_PASSWORD') |
        Where-Object { $vorhanden -notmatch "(?m)^\s*$_\s*=" }
    if ($fehlend) {
        if ($Stoppen) { throw 'Die lokale Keycloak-Konfiguration ist unvollstaendig. Zuerst Keycloak.ps1 ohne -Stoppen ausfuehren.' }
        $zufall = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $werte = foreach ($name in $fehlend) {
                $bytes = New-Object byte[] 32
                $zufall.GetBytes($bytes)
                "$name='$([Convert]::ToBase64String($bytes))'"
            }
            $trennung = if ($vorhanden.Length -gt 0 -and -not $vorhanden.EndsWith("`n")) { [Environment]::NewLine } else { '' }
            [System.IO.File]::AppendAllText($envDatei, $trennung + ($werte -join [Environment]::NewLine) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
        } finally { $zufall.Dispose() }
        Write-Host 'Fehlende lokale Startpasswoerter wurden in pemonline/.env ergaenzt (von Git ausgeschlossen). Bestehende Werte bleiben erhalten.'
    }
    if ($Stoppen) {
        & docker compose stop keycloak
        if ($LASTEXITCODE -ne 0) { throw 'Keycloak konnte nicht gestoppt werden.' }
        return
    }
    & docker compose up -d keycloak
    if ($LASTEXITCODE -ne 0) { throw 'Keycloak konnte nicht gestartet werden. Laeuft Docker Desktop?' }
    Write-Host 'Warte auf den Realm pemonline ...'
    $bereit = $false
    for ($versuch = 0; $versuch -lt 90; $versuch++) {
        try {
            $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8180/realms/pemonline/.well-known/openid-configuration' -TimeoutSec 2
            $bereit = $true
            break
        } catch { Start-Sleep -Seconds 2 }
    }
    if (-not $bereit) { throw 'Keycloak ist noch nicht bereit. Diagnose: docker compose logs keycloak' }
    Write-Host 'Keycloak ist bereit. Die Anwendung jetzt mit .\Starten.ps1 starten.'
    Write-Host 'Anwendung: http://127.0.0.1:8080 - Benutzer admin, Startpasswort PEM_KEYCLOAK_APP_PASSWORD aus .env.'
    Write-Host 'Keycloak-Verwaltung: http://127.0.0.1:8180 - Benutzer keycloak-admin, Passwort PEM_KEYCLOAK_ADMIN_PASSWORD aus .env.'
    Write-Host 'Der Realm wird nur beim ersten Start importiert. Vorhandene Benutzer bleiben erhalten.'
} finally { Pop-Location }
