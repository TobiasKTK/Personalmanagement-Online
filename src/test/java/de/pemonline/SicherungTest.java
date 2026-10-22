package de.pemonline;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class SicherungTest {
    @TempDir Path verzeichnis;

    @Test void alteProjektdateiWirdGeprueftKopiertUndEinBestehendesZielNichtUeberschrieben() throws Exception {
        Path projekt = Files.createDirectory(verzeichnis.resolve("projekt"));
        Path alt = Files.createDirectory(projekt.resolve("data"));
        Path ziel = verzeichnis.resolve("betrieb");
        Files.copy(Path.of("Betriebsdaten.ps1"), projekt.resolve("Betriebsdaten.ps1"));
        Files.writeString(projekt.resolve("Uebernahme.ps1"), """
                param([string]$DatenVerzeichnis)
                $ErrorActionPreference = 'Stop'
                . (Join-Path $PSScriptRoot 'Betriebsdaten.ps1')
                Initialize-Betriebsdaten $DatenVerzeichnis | Out-Null
                """);
        try (var c = DriverManager.getConnection(url(alt), "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE pruefung (wert INTEGER)");
            s.execute("INSERT INTO pruefung VALUES (42)");
        }
        assertThat(powershell(projekt.resolve("Uebernahme.ps1"), List.of("-DatenVerzeichnis", ziel.toString()))).isZero();
        assertThat(Files.mismatch(alt.resolve("mitarbeiter.mv.db"), ziel.resolve("mitarbeiter.mv.db"))).isEqualTo(-1);
        try (var c = DriverManager.getConnection(url(ziel), "sa", ""); var s = c.createStatement()) {
            s.execute("UPDATE pruefung SET wert = 43");
        }
        assertThat(powershell(projekt.resolve("Uebernahme.ps1"), List.of("-DatenVerzeichnis", ziel.toString()))).isZero();
        try (var c = DriverManager.getConnection(url(ziel), "sa", ""); var s = c.createStatement(); var r = s.executeQuery("SELECT wert FROM pruefung")) {
            r.next();
            assertThat(r.getInt(1)).isEqualTo(43);
        }
        assertThat(Files.exists(alt.resolve("mitarbeiter.mv.db"))).isTrue();
    }

    @Test void echteDatenbankSichernWiederherstellenUndFehlerAbweisen() throws Exception {
        Path quelle = Files.createDirectory(verzeichnis.resolve("quelle"));
        Path ziel = verzeichnis.resolve("ziel");
        Path backup = verzeichnis.resolve("sicherung.mv.db");
        try (var c = DriverManager.getConnection(url(quelle), "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE pruefung (wert VARCHAR(100))");
            s.execute("INSERT INTO pruefung VALUES ('Sicherung vollständig')");
            assertThat(aufrufen("Sichern", backup, quelle)).isNotZero();
            assertThat(Files.exists(backup)).isFalse();
        }
        assertThat(aufrufen("Sichern", backup, quelle)).isZero();
        assertThat(aufrufen("Wiederherstellen", backup, ziel)).isZero();
        try (var c = DriverManager.getConnection(url(ziel), "sa", ""); var s = c.createStatement(); var r = s.executeQuery("SELECT wert FROM pruefung")) {
            r.next();
            assertThat(r.getString(1)).isEqualTo("Sicherung vollständig");
        }
        assertThat(aufrufen("Wiederherstellen", backup, ziel)).isNotZero();
        Files.writeString(backup, "Beschädigt");
        assertThat(aufrufen("Wiederherstellen", backup, verzeichnis.resolve("beschaedigt"))).isNotZero();
        assertThat(Files.exists(verzeichnis.resolve("beschaedigt/mitarbeiter.mv.db"))).isFalse();
    }

    @Test void relativeSicherungUndWiederherstellungFolgenPowerShellCd() throws Exception {
        Path aufrufort = Files.createDirectory(verzeichnis.resolve("aufruf ort"));
        Path quelle = Files.createDirectory(aufrufort.resolve("daten"));
        try (var c = DriverManager.getConnection(url(quelle), "sa", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE pruefung (wert INTEGER)");
            s.execute("INSERT INTO pruefung VALUES (42)");
        }
        Path wrapper = verzeichnis.resolve("RelativeSicherung.ps1");
        Files.writeString(wrapper, """
                param([string]$Skript, [string]$Aufrufort)
                $ErrorActionPreference = 'Stop'
                Set-Location -LiteralPath $Aufrufort
                & $Skript -Aktion Sichern -Datei './kopie.mv.db' -DatenVerzeichnis './daten'
                & $Skript -Aktion Wiederherstellen -Datei './kopie.mv.db' -DatenVerzeichnis './wiederhergestellt'
                """);
        assertThat(powershell(wrapper, List.of("-Skript", Path.of("Sicherung.ps1").toAbsolutePath().toString(),
                "-Aufrufort", aufrufort.toString()))).isZero();
        assertThat(Files.exists(aufrufort.resolve("kopie.mv.db.sha256"))).isTrue();
        try (var c = DriverManager.getConnection(url(aufrufort.resolve("wiederhergestellt")), "sa", "");
                var s = c.createStatement(); var r = s.executeQuery("SELECT wert FROM pruefung")) {
            r.next();
            assertThat(r.getInt(1)).isEqualTo(42);
        }
    }

    @Test void startLoestRelativeParameterUndUmgebungswerteVorSeinemVerzeichniswechselAuf() throws Exception {
        Path projekt = Files.createDirectory(verzeichnis.resolve("projekt"));
        Path aufrufort = Files.createDirectory(verzeichnis.resolve("aufruf ort"));
        Files.copy(Path.of("Starten.ps1"), projekt.resolve("Starten.ps1"));
        Files.copy(Path.of("Betriebsdaten.ps1"), projekt.resolve("Betriebsdaten.ps1"));
        Path target = Files.createDirectory(projekt.resolve("target"));
        try (var jar = new java.util.zip.ZipOutputStream(Files.newOutputStream(target.resolve("pemonline-1.0.0.jar")))) {
            for (String entry : List.of("org/springframework/boot/loader/launch/JarLauncher.class", "BOOT-INF/classes/static/index.html")) {
                jar.putNextEntry(new java.util.zip.ZipEntry(entry));
                jar.closeEntry();
            }
        }
        Path wrapper = verzeichnis.resolve("RelativerStart.ps1");
        Files.writeString(wrapper, """
                param([string]$Skript, [string]$Aufrufort)
                $ErrorActionPreference = 'Stop'
                Set-Location -LiteralPath $Aufrufort
                $env:MITARBEITER_DB_URL = ''
                # Der Pfadtest benoetigt keinen externen Anmeldedienst.
                $env:PEM_ANMELDUNG = 'lokal'
                function java {
                    $args | Set-Content -LiteralPath (Join-Path $Aufrufort 'java-argumente.txt') -Encoding UTF8
                    $global:LASTEXITCODE = 0
                }
                $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
                $listener.Start()
                $port = $listener.LocalEndpoint.Port
                $listener.Stop()
                & $Skript -OhneBuild -Port $port -DatenVerzeichnis './parameter'
                $env:MITARBEITER_DATENVERZEICHNIS = './umgebung'
                & $Skript -OhneBuild -Port $port
                """);
        assertThat(powershell(wrapper, List.of("-Skript", projekt.resolve("Starten.ps1").toString(),
                "-Aufrufort", aufrufort.toString()))).isZero();
        assertThat(Files.isDirectory(aufrufort.resolve("parameter"))).isTrue();
        assertThat(Files.isDirectory(aufrufort.resolve("umgebung"))).isTrue();
        assertThat(Files.exists(projekt.resolve("parameter"))).isFalse();
        assertThat(Files.exists(projekt.resolve("umgebung"))).isFalse();
        var argumente = Files.readString(aufrufort.resolve("java-argumente.txt"));
        assertThat(argumente).contains("--spring.datasource.url=jdbc:h2:file:"
                + aufrufort.resolve("umgebung/mitarbeiter").toString().replace('\\', '/'));
    }

    private String url(Path ordner) { return "jdbc:h2:file:" + ordner.resolve("mitarbeiter").toString().replace('\\', '/'); }
    private int aufrufen(String aktion, Path datei, Path daten) throws Exception {
        return powershell(Path.of("Sicherung.ps1"), List.of("-Aktion", aktion, "-Datei", datei.toString(), "-DatenVerzeichnis", daten.toString()));
    }
    private int powershell(Path skript, List<String> argumente) throws Exception {
        Path log = Files.createTempFile(verzeichnis, "powershell-", ".log");
        var befehl = new java.util.ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", skript.toAbsolutePath().toString()));
        befehl.addAll(argumente);
        var prozess = new ProcessBuilder(befehl)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(prozess.waitFor(30, TimeUnit.SECONDS)).as("PowerShell muss innerhalb von 30 Sekunden enden").isTrue();
            return prozess.exitValue();
        } finally {
            if (prozess.isAlive()) prozess.destroyForcibly();
        }
    }
}
