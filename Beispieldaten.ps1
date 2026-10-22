[CmdletBinding()]
param(
    [string]$Adresse = 'http://127.0.0.1:8080',
    [string]$Kontoname,
    [Nullable[long]]$MandantenspeicherId,
    [switch]$NurPruefen,
    [string]$Bericht
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Betriebsdaten.ps1')
if (-not $Bericht) { $Bericht = Join-Path $PSScriptRoot 'target/beispieldaten-500-bericht.json' }
$Bericht = Resolve-DateisystemPfad $Bericht
$node = Join-Path $PSScriptRoot '.tools/node/node.exe'
$skript = Join-Path $PSScriptRoot 'scripts/beispieldaten.cjs'
if (-not (Test-Path -LiteralPath $node)) { throw 'Bitte zuerst Starten.ps1 ausfuehren, um die lokalen Werkzeuge bereitzustellen.' }
New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'target') -Force | Out-Null
$sperre = [System.IO.File]::Open((Join-Path $PSScriptRoot 'target/beispieldaten-500.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$prozess = $null
$passwort = $null
try {
    if (-not $Kontoname) { $Kontoname = Read-Host 'Administrations-Kontoname' }
    $passwort = Read-Host 'Passwort (wird nicht angezeigt oder gespeichert)' -AsSecureString
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $node
    $start.Arguments = '"' + $skript + '"'
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.CreateNoWindow = $true
    $prozess = [System.Diagnostics.Process]::Start($start)
    $konfiguration = @{
        adresse = $Adresse; kontoname = $Kontoname
        mandantenspeicherId = $MandantenspeicherId
        passwort = [System.Net.NetworkCredential]::new('', $passwort).Password
        nurPruefen = [bool]$NurPruefen; bericht = $Bericht
    }
    $daten = [System.Text.Encoding]::UTF8.GetBytes(($konfiguration | ConvertTo-Json -Compress))
    try {
        # Windows PowerShell 5 hat noch kein ProcessStartInfo.StandardInputEncoding.
        $prozess.StandardInput.BaseStream.Write($daten, 0, $daten.Length)
        $prozess.StandardInput.BaseStream.Flush()
    }
    finally {
        [Array]::Clear($daten, 0, $daten.Length)
        $konfiguration.passwort = $null
        $prozess.StandardInput.Close()
    }
    $prozess.WaitForExit()
    if ($prozess.ExitCode -ne 0) { throw "Import oder Pruefung fehlgeschlagen. Einzelheiten: $Bericht" }
    Write-Host "Bericht: $Bericht"
    Write-Host 'In der Teamuebersicht aktualisieren und nach BEISPIEL500 suchen.'
} finally {
    if ($null -ne $prozess) { $prozess.Dispose() }
    if ($null -ne $passwort) { $passwort.Dispose() }
    $sperre.Dispose()
}
