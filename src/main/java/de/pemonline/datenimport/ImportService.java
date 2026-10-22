package de.pemonline.datenimport;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.mitarbeiter.MitarbeiterEingabe;
import de.pemonline.mitarbeiter.MitarbeiterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.*;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;
import static de.pemonline.datenimport.ImportModel.*;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
@Transactional(readOnly = true)
public class ImportService {
    private final JdbcClient jdbc;
    private final ImportPruefung pruefung;
    private final MitarbeiterService mitarbeiter;
    private final ObjectMapper json;
    private final Clock clock;
    public ImportService(JdbcClient jdbc, ImportPruefung pruefung, MitarbeiterService mitarbeiter, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.pruefung = pruefung; this.mitarbeiter = mitarbeiter; this.json = json; this.clock = clock;
    }
    public CsvLeser.Tabelle spalten(@Valid @NotNull Eingabe eingabe) {
        var t = CsvLeser.lesen(eingabe.csv(), eingabe.trennzeichen());
        return new CsvLeser.Tabelle(t.spalten(), t.zeilen().stream().limit(5).toList());
    }
    @Transactional
    public Vorschau vorschau(@Valid @NotNull Eingabe eingabe) {
        var zeilen = pruefung.pruefen(eingabe, CsvLeser.lesen(eingabe.csv(), eingabe.trennzeichen()));
        int fehler = zeilen.stream().mapToInt(z -> z.fehler().size()).sum();
        // Die Firmen-Kontozeile serialisiert den Austausch auch ohne bereits vorhandene Vorschau.
        jdbc.sql("SELECT username FROM users WHERE username = :konto FOR UPDATE")
                .param("konto", konto()).query(String.class).single();
        jdbc.sql("DELETE FROM import_vorschau WHERE uebernommen = FALSE AND (kontoname = :konto OR erstellt_am < :ablauf)")
                .param("konto", konto()).param("ablauf", clock.instant().minus(Duration.ofMinutes(30))).update();
        if (fehler > 0) return new Vorschau(null, null, zeilen, fehler);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO import_vorschau (id, kontoname, erstellt_am, inhalt, anzahl) VALUES (:id, :konto, :zeit, :inhalt, :anzahl)")
                .param("id", id).param("konto", konto()).param("zeit", clock.instant())
                .param("inhalt", json.writeValueAsString(zeilen.stream().map(z -> z.daten()).toList()))
                .param("anzahl", zeilen.size()).update();
        return new Vorschau(id, clock.instant().plus(Duration.ofMinutes(30)), zeilen, 0);
    }
    @Transactional
    public Ergebnis uebernehmen(@NotNull UUID id) {
        var auftrag = jdbc.sql("SELECT * FROM import_vorschau WHERE id = :id AND kontoname = :konto FOR UPDATE")
                .param("id", id.toString()).param("konto", konto()).query(Auftrag.class).optional()
                .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Importvorschau nicht gefunden. Bitte erneut prüfen."));
        if (auftrag.uebernommen()) return new Ergebnis(auftrag.id(), auftrag.anzahl());
        if (!auftrag.erstelltAm().plus(Duration.ofMinutes(30)).isAfter(clock.instant()))
            throw new FachlicherFehler(KONFLIKT, "Die Importvorschau ist abgelaufen. Bitte erneut prüfen.");
        var daten = json.readValue(auftrag.inhalt(), MitarbeiterEingabe[].class);
        // Die vorhandenen Service-Transaktionen nehmen an dieser Gesamttransaktion teil.
        // Ein Konflikt in irgendeiner Zeile rollt Mitarbeiter, Historie, Verlauf und Outbox zurück.
        Arrays.stream(daten).forEach(mitarbeiter::anlegen);
        jdbc.sql("UPDATE import_vorschau SET uebernommen = TRUE, inhalt = '' WHERE id = :id").param("id", auftrag.id()).update();
        return new Ergebnis(auftrag.id(), auftrag.anzahl());
    }
    private String konto() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
}
