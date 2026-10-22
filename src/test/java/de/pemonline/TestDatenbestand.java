package de.pemonline;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

/** Vergleicht die fachlich betroffenen Tabellen vor und nach einer abgelehnten HTTP-Anfrage. */
final class TestDatenbestand {
    private final Map<String, List<List<String>>> tabellen = new LinkedHashMap<>();

    static TestDatenbestand sichern(JdbcClient jdbc, String... tabellen) {
        var bestand = new TestDatenbestand();
        for (String tabelle : tabellen) bestand.tabellen.put(tabelle, lesen(jdbc, tabelle));
        return bestand;
    }

    void unveraendert(JdbcClient jdbc) {
        tabellen.forEach((tabelle, vorher) -> assertThat(lesen(jdbc, tabelle))
            .as("Unveränderter Datenbestand in %s, einschließlich Versionen und Protokollen", tabelle)
            .containsExactlyInAnyOrderElementsOf(vorher));
    }

    private static List<List<String>> lesen(JdbcClient jdbc, String tabelle) {
        // Namen stammen ausschließlich aus Testkonstanten. getString vergleicht auch CLOB-Inhalte.
        // Identitätssequenzen gehören nicht zum Vergleich: Datenbanken dürfen dabei Lücken lassen.
        return jdbc.sql("SELECT * FROM " + tabelle).query((rs, n) -> {
            List<String> zeile = new ArrayList<>();
            for (int spalte = 1; spalte <= rs.getMetaData().getColumnCount(); spalte++) zeile.add(rs.getString(spalte));
            return zeile;
        }).list();
    }
}
