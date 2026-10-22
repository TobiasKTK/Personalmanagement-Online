package de.pemonline.datenimport;

import de.pemonline.fachlich.FachlicherFehler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static de.pemonline.fachlich.FachlicherFehler.Art.UNGUELTIG;

/** Begrenzter CSV-Leser mit RFC-4180-Quoting; zusätzlich Semikolon, Tabulator und LF. */
public final class CsvLeser {
    private CsvLeser() {}
    public record Tabelle(List<String> spalten, List<List<String>> zeilen) {}

    public static Tabelle lesen(String csv, String trennzeichen) {
        if (csv == null || csv.isBlank() || csv.length() > 1_048_576
                || csv.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw fehler("Bitte eine nicht leere UTF-8-CSV mit höchstens 1 MiB auswählen.");
        if (trennzeichen == null || !List.of(";", ",", "\t").contains(trennzeichen))
            throw fehler("Als Trennzeichen sind Semikolon, Komma und Tabulator erlaubt.");
        if (csv.indexOf('\uFFFD') >= 0 || csv.indexOf('\0') >= 0) throw fehler("Die Datei enthält ungültige Zeichen. Bitte als UTF-8 speichern.");
        if (csv.charAt(0) == '\uFEFF') csv = csv.substring(1);
        char trenner = trennzeichen.charAt(0);
        var zeilen = new ArrayList<List<String>>();
        var zeile = new ArrayList<String>();
        var feld = new StringBuilder();
        int zustand = 0; // 0 Feldanfang, 1 unquotiert, 2 quotiert, 3 geschlossen
        boolean letzteZeileOffen = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            letzteZeileOffen = true;
            if (zustand == 2) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { feld.append('"'); i++; }
                    else zustand = 3;
                } else feld.append(c);
            } else if (c == trenner || c == '\n' || c == '\r') {
                zeile.add(feld.toString()); feld.setLength(0); zustand = 0;
                if (zeile.size() > 32) throw fehler("Höchstens 32 Spalten sind erlaubt.");
                if (c != trenner) {
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                    zeileHinzufuegen(zeilen, zeile); zeile = new ArrayList<>(); letzteZeileOffen = false;
                }
            } else if (c == '"' && zustand == 0) zustand = 2;
            else {
                if (c == '"' || zustand == 3) throw fehler("Ungültige CSV-Anführungszeichen. Doppelte Anführungszeichen innerhalb eines Feldes verdoppeln.");
                feld.append(c); zustand = 1;
            }
            if (feld.length() > 10_000) throw fehler("Ein CSV-Feld ist zu lang (höchstens 10.000 Zeichen).");
        }
        if (zustand == 2) throw fehler("Ein CSV-Feld hat kein schließendes Anführungszeichen.");
        if (letzteZeileOffen) { zeile.add(feld.toString()); zeileHinzufuegen(zeilen, zeile); }
        if (zeilen.size() < 2) throw fehler("Die CSV benötigt eine Kopfzeile und mindestens eine Datenzeile.");
        var kopf = zeilen.removeFirst();
        if (kopf.size() > 32 || kopf.stream().anyMatch(s -> s == null || s.isBlank()))
            throw fehler("Jede Spalte benötigt einen Namen; höchstens 32 Spalten sind erlaubt.");
        for (int i = 0; i < zeilen.size(); i++) if (zeilen.get(i).size() != kopf.size())
            throw fehler("Datensatz " + (i + 2) + " hat eine andere Spaltenzahl als die Kopfzeile.");
        return new Tabelle(List.copyOf(kopf), List.copyOf(zeilen));
    }

    private static void zeileHinzufuegen(List<List<String>> zeilen, List<String> zeile) {
        if (zeile.size() == 1 && zeile.getFirst().isBlank()) return;
        zeilen.add(List.copyOf(zeile));
        if (zeilen.size() > 501) throw fehler("Pro Import sind höchstens 500 Datenzeilen erlaubt.");
    }
    private static FachlicherFehler fehler(String meldung) { return new FachlicherFehler(UNGUELTIG, meldung); }
}
