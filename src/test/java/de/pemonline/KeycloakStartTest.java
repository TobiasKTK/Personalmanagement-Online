package de.pemonline;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class KeycloakStartTest {
    @TempDir Path ordner;

    @Test void vorhandeneEnvWirdErgaenztOhneKonfigurationOderPasswoerterZuErsetzen() throws Exception {
        Files.copy(Path.of("Keycloak.ps1"), ordner.resolve("Keycloak.ps1"));
        String vorhanden = "# Bestehende Konfiguration\nPEM_ANMELDUNG=lokal\nPEM_KEYCLOAK_ADMIN_PASSWORD='Beibehalten-Test'";
        Path env = ordner.resolve(".env");
        Files.writeString(env, vorhanden);
        Path wrapper = ordner.resolve("Starttest.ps1");
        Files.writeString(wrapper, """
                $ErrorActionPreference = 'Stop'
                function docker {
                    if ($args -contains 'up') {
                        $text = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot '.env'))
                        foreach ($name in @('PEM_KEYCLOAK_ADMIN_PASSWORD', 'PEM_KEYCLOAK_APP_PASSWORD')) {
                            if ($text -notmatch "(?m)^$name=.+$") { throw 'Fehlende Compose-Konfiguration.' }
                        }
                    }
                    $global:LASTEXITCODE = 0
                }
                function Invoke-RestMethod { return @{} }
                & (Join-Path $PSScriptRoot 'Keycloak.ps1')
                """);
        ausfuehren(wrapper);
        String ergaenzt = Files.readString(env);
        assertThat(ergaenzt).startsWith(vorhanden + System.lineSeparator());
        var muster = java.util.regex.Pattern.compile("(?m)^PEM_KEYCLOAK_APP_PASSWORD='([A-Za-z0-9+/=]+)'\\r?$").matcher(ergaenzt);
        assertThat(muster.find()).isTrue();
        assertThat(java.util.Base64.getDecoder().decode(muster.group(1))).hasSize(32);
        ausfuehren(wrapper);
        assertThat(Files.readString(env)).isEqualTo(ergaenzt);
    }

    private void ausfuehren(Path skript) throws Exception {
        Path log = Files.createTempFile(ordner, "start-", ".log");
        var prozess = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", skript.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(prozess.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(prozess.exitValue()).as(Files.readString(log)).isZero();
        } finally { if (prozess.isAlive()) prozess.destroyForcibly(); }
    }
}
