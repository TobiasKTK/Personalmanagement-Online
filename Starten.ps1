[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [switch]$OhneBuild,
    [switch]$NurBuild,
    [string]$DatenVerzeichnis
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Betriebsdaten.ps1')
$datenOrdner = $null
if (-not $NurBuild -and ($DatenVerzeichnis -or -not $env:MITARBEITER_DB_URL)) {
    # Relative Parameter und Umgebungswerte beziehen sich auf den Aufrufort, auch vor einem Build.
    $datenOrdner = Get-BetriebsdatenVerzeichnis $DatenVerzeichnis
}

function Test-AusfuehrbareJar([string]$Pfad) {
    if (-not (Test-Path -LiteralPath $Pfad -PathType Leaf)) {
        throw 'Keine ausfuehrbare Anwendung vorhanden. Starten.ps1 ohne -OhneBuild ausfuehren.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archiv = [System.IO.Compression.ZipFile]::OpenRead($Pfad)
    try {
        if ($null -eq $archiv.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class') -or
            $null -eq $archiv.GetEntry('BOOT-INF/classes/static/index.html')) {
            throw 'Die JAR ist unvollstaendig. Der vorherige Build wurde nicht abgeschlossen. Starten.ps1 ohne -OhneBuild ausfuehren.'
        }
    } finally {
        $archiv.Dispose()
    }
}

$buildSperre = $null
Push-Location -LiteralPath $PSScriptRoot
try {
    if ($NurBuild -and $OhneBuild) { throw '-NurBuild und -OhneBuild sind nicht kombinierbar.' }
    if (-not $NurBuild) {
        $belegt = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
            Where-Object Port -EQ $Port
        if ($belegt) {
            throw "Port $Port ist bereits belegt. Die bisherige Anwendung zuerst mit Strg+C oder der Stopptaste in VS Code beenden."
        }
        if ($env:PEM_ANMELDUNG -ne 'lokal') {
            $issuer = if ($env:PEM_KEYCLOAK_ISSUER) { $env:PEM_KEYCLOAK_ISSUER.TrimEnd('/') } else { 'http://127.0.0.1:8180/realms/pemonline' }
            try {
                $null = Invoke-RestMethod -Uri "$issuer/.well-known/openid-configuration" -TimeoutSec 5
            } catch {
                throw "Keycloak ist unter $issuer nicht erreichbar. Zuerst Docker Desktop und .\Keycloak.ps1 starten oder PEM_KEYCLOAK_ISSUER pruefen."
            }
        }
    }
    if (-not $OhneBuild) {
        New-Item -ItemType Directory -Path 'target' -Force | Out-Null
        try {
            $buildSperre = [System.IO.File]::Open((Join-Path $PSScriptRoot 'target/start-build.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
        } catch [System.IO.IOException] {
            throw 'Ein anderer Startvorgang baut die Anwendung bereits. Bitte dessen Abschluss abwarten.'
        }
        Write-Host 'Baue die aktuelle Anwendung. Bitte auf BUILD SUCCESS warten.'
        & .\mvnw.cmd -B -ntp -Pstart -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw 'Der Maven-Build ist fehlgeschlagen. Bitte die Meldungen oben pruefen.'
        }
        $gebaut = Join-Path $PSScriptRoot 'target/start/pemonline-1.0.0.jar'
        Test-AusfuehrbareJar $gebaut
        Copy-Item -LiteralPath $gebaut -Destination 'target/pemonline-1.0.0.jar' -Force
        $buildSperre.Dispose()
        $buildSperre = $null
    }
    $jar = Join-Path $PSScriptRoot 'target/pemonline-1.0.0.jar'
    Test-AusfuehrbareJar $jar
    if ($NurBuild) {
        Write-Host 'Build abgeschlossen. Start mit: .\Starten.ps1 -OhneBuild'
        return
    }
    Write-Host "Starte die Anwendung fuer http://127.0.0.1:$Port"
    Write-Host 'Erst nach der Meldung Started PemonlineApplication im Browser neu laden (Strg+F5).'
    Write-Host 'Zum Beenden Strg+C druecken.'
    $datenArgumente = @()
    if ($datenOrdner) {
        $datenOrdner = Initialize-Betriebsdaten $datenOrdner
        $datenPfad = (Join-Path $datenOrdner 'mitarbeiter').Replace('\', '/')
        $datenArgumente = @("--spring.datasource.url=jdbc:h2:file:$datenPfad", "--logging.file.name=$datenOrdner/logs/pemonline.log")
        Write-Host "Betriebsdaten: $datenOrdner"
    }
    & java '-Dfile.encoding=UTF-8' -jar target/pemonline-1.0.0.jar "--server.port=$Port" @datenArgumente
    if ($LASTEXITCODE -ne 0) {
        throw 'Die Anwendung konnte nicht erfolgreich ausgefuehrt werden.'
    }
}
finally {
    if ($null -ne $buildSperre) { $buildSperre.Dispose() }
    Pop-Location
}
