param(
    [ValidateSet('start', 'build', 'test')]
    [string]$Aufgabe = 'start'
)

$ErrorActionPreference = 'Stop'
$alterPfad = $env:PATH
Push-Location $PSScriptRoot
try {
    if (!(Test-Path -LiteralPath '.tools/node/node_modules/npm/bin/npm-cli.js')) {
        & '.\mvnw.cmd' -B -ntp frontend:install-node-and-npm
        if ($LASTEXITCODE -ne 0) { throw 'Node.js konnte nicht vorbereitet werden.' }
    }
    $nodeOrdner = (Resolve-Path '.tools/node').Path
    $node = Join-Path $nodeOrdner 'node.exe'
    $npm = Join-Path $nodeOrdner 'node_modules/npm/bin/npm-cli.js'
    $env:PATH = $nodeOrdner + ';' + $env:PATH
    Push-Location 'frontend'
    try {
        & $node $npm run vorbereiten
        if ($LASTEXITCODE -ne 0) { throw 'Frontend-Abhängigkeiten konnten nicht installiert werden.' }
        & $node $npm run $Aufgabe
        if ($LASTEXITCODE -ne 0) { throw "Frontend-Aufgabe '$Aufgabe' fehlgeschlagen." }
    } finally {
        Pop-Location
    }
} finally {
    $env:PATH = $alterPfad
    Pop-Location
}
