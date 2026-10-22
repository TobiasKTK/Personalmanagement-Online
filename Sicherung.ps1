param(
    [ValidateSet('Sichern', 'Wiederherstellen')]
    [string]$Aktion = 'Sichern',
    [Parameter(Mandatory = $true)]
    [string]$Datei,
    [string]$DatenVerzeichnis
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Betriebsdaten.ps1')
$ordner = Get-BetriebsdatenVerzeichnis $DatenVerzeichnis
$datenbank = Join-Path $ordner 'mitarbeiter.mv.db'
$sicherung = Resolve-DateisystemPfad $Datei
$pruefsumme = "$sicherung.sha256"
if ($Aktion -eq 'Sichern') {
    if (Test-Path -LiteralPath $pruefsumme) { throw 'Die Pruefsummendatei existiert bereits. Einen neuen Sicherungsnamen verwenden.' }
    Copy-GeschlosseneDatenbank $datenbank $sicherung
    [System.IO.File]::WriteAllText($pruefsumme, (Get-FileHash -LiteralPath $sicherung -Algorithm SHA256).Hash)
    Write-Host "Sicherung mit Pruefsumme erstellt: $sicherung"
} else {
    if (Test-Path -LiteralPath $datenbank) {
        throw 'Das Ziel enthaelt bereits eine Datenbank. Fuer die Wiederherstellung ein neues, leeres Datenverzeichnis verwenden.'
    }
    if (-not (Test-Path -LiteralPath $pruefsumme -PathType Leaf)) { throw 'Die SHA256-Pruefsumme zur Sicherung fehlt.' }
    $erwartet = [System.IO.File]::ReadAllText($pruefsumme).Trim()
    if ($erwartet -notmatch '^[A-Fa-f0-9]{64}$' -or (Get-FileHash -LiteralPath $sicherung -Algorithm SHA256).Hash -ne $erwartet) {
        throw 'Die Sicherung stimmt nicht mit ihrer Pruefsumme ueberein.'
    }
    New-Item -ItemType Directory -Path $ordner -Force | Out-Null
    Copy-GeschlosseneDatenbank $sicherung $datenbank
    Write-Host "Datenbank wiederhergestellt: $datenbank"
    Write-Host 'Beim Start dieses Datenverzeichnis mit -DatenVerzeichnis angeben.'
}
