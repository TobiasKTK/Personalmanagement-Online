param([switch]$OhneBrowser)
$ErrorActionPreference = 'Stop'
$vorherigesTestJar = $env:PEM_TEST_JAR
Push-Location -LiteralPath $PSScriptRoot
try {
    & .\mvnw.cmd -B -ntp -Pstart verify
    if ($LASTEXITCODE -ne 0) { throw 'Build oder Java-Tests fehlgeschlagen.' }
    # Alle Browser-Suiten prüfen genau das soeben gebaute Artefakt.
    $env:PEM_TEST_JAR = (Resolve-Path -LiteralPath 'target/start/pemonline-1.0.0.jar').Path
    Push-Location -LiteralPath 'frontend'
    try {
        & ..\.tools\node\node.exe --test scripts/abhaengigkeiten.test.cjs
        if ($LASTEXITCODE -ne 0) { throw 'Installationspruefungen fehlgeschlagen.' }
        & ..\.tools\node\node.exe --test scripts/bauen.test.cjs
        if ($LASTEXITCODE -ne 0) { throw 'Frontend-Buildpruefungen fehlgeschlagen.' }
        if (-not $OhneBrowser) {
            & ..\.tools\node\node.exe node_modules/@playwright/test/cli.js test
            if ($LASTEXITCODE -ne 0) { throw 'Browsertests fehlgeschlagen.' }
            & ..\.tools\node\node.exe node_modules/@playwright/test/cli.js test --config=playwright.email.config.ts
            if ($LASTEXITCODE -ne 0) { throw 'E-Mail-Browsertest fehlgeschlagen.' }
            & ..\.tools\node\node.exe node_modules/@playwright/test/cli.js test --config=playwright.keycloak.config.ts
            if ($LASTEXITCODE -ne 0) { throw 'Keycloak-Browsertests fehlgeschlagen.' }
        }
    } finally { Pop-Location }
} finally { $env:PEM_TEST_JAR = $vorherigesTestJar; Pop-Location }
