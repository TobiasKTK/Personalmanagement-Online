$ErrorActionPreference = 'Stop'

function Resolve-DateisystemPfad([string]$Pfad) {
    $anbieter = $null
    $laufwerk = $null
    # PowerShell-cd aendert nicht zwingend das Arbeitsverzeichnis der .NET-Dateifunktionen.
    $absolut = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Pfad, [ref]$anbieter, [ref]$laufwerk)
    if ($anbieter.Name -ne 'FileSystem') { throw 'Bitte einen Dateisystempfad angeben.' }
    return $absolut
}

function Get-BetriebsdatenVerzeichnis([string]$DatenVerzeichnis) {
    if ([string]::IsNullOrWhiteSpace($DatenVerzeichnis)) {
        $DatenVerzeichnis = $env:MITARBEITER_DATENVERZEICHNIS
    }
    if ([string]::IsNullOrWhiteSpace($DatenVerzeichnis)) {
        if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'Bitte -DatenVerzeichnis angeben.' }
        $DatenVerzeichnis = Join-Path $env:LOCALAPPDATA 'pemonline'
    }
    $aufgeloest = Resolve-DateisystemPfad $DatenVerzeichnis
    if ($aufgeloest.Contains(';')) { throw 'Das Datenverzeichnis darf kein Semikolon enthalten.' }
    return $aufgeloest
}

function Copy-GeschlosseneDatenbank([string]$Quelle, [string]$Ziel) {
    # Exklusiver Zugriff verhindert eine inkonsistente Kopie einer gerade verwendeten H2-Datei.
    $eingabe = $null
    $ausgabe = $null
    $teil = "$Ziel.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        if (Test-Path -LiteralPath $Ziel) { throw 'Die Zieldatei existiert bereits.' }
        $eingabe = [System.IO.File]::Open($Quelle, 'Open', 'Read', 'None')
        $ausgabe = [System.IO.File]::Open($teil, 'CreateNew', 'ReadWrite', 'None')
        $eingabe.CopyTo($ausgabe)
        $ausgabe.Flush($true)
        $eingabe.Position = 0
        $ausgabe.Position = 0
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $originalHash = [Convert]::ToBase64String($sha.ComputeHash($eingabe))
            $kopieHash = [Convert]::ToBase64String($sha.ComputeHash($ausgabe))
            if ($originalHash -ne $kopieHash) { throw 'Die Datenbankkopie konnte nicht verifiziert werden.' }
        } finally { $sha.Dispose() }
        $ausgabe.Dispose()
        $ausgabe = $null
        [System.IO.File]::Move($teil, $Ziel)
    } finally {
        if ($null -ne $ausgabe) { $ausgabe.Dispose() }
        if ($null -ne $eingabe) { $eingabe.Dispose() }
        if (Test-Path -LiteralPath $teil) { Remove-Item -LiteralPath $teil }
    }
}

function Initialize-Betriebsdaten([string]$DatenVerzeichnis) {
    $zielOrdner = Get-BetriebsdatenVerzeichnis $DatenVerzeichnis
    New-Item -ItemType Directory -Path $zielOrdner -Force | Out-Null
    $alt = Join-Path $PSScriptRoot 'data/mitarbeiter.mv.db'
    $neu = Join-Path $zielOrdner 'mitarbeiter.mv.db'
    if ((Test-Path -LiteralPath $alt -PathType Leaf) -and
        -not (Test-Path -LiteralPath $neu) -and
        [System.IO.Path]::GetFullPath($alt) -ne [System.IO.Path]::GetFullPath($neu)) {
        Copy-GeschlosseneDatenbank $alt $neu
        Write-Host 'Vorhandene Datenbank uebernommen. Die urspruengliche Datei bleibt als Sicherung erhalten.'
    }
    return $zielOrdner
}
