package de.pemonline.personaldaten;

import static de.pemonline.personaldaten.ErweitertePersonaldatenModel.*;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.fachlich.*;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.RoundingMode;
import java.time.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

@Service
@Validated
@Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class ErweitertePersonaldatenService {
    private final JdbcClient jdbc;
    private final MitarbeiterRepository mitarbeiter;
    private final ObjectMapper json;
    private final Clock clock;
    public ErweitertePersonaldatenService(JdbcClient jdbc, MitarbeiterRepository mitarbeiter, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.mitarbeiter = mitarbeiter; this.json = json; this.clock = clock;
    }
    public Akte lesen(@Positive long person) {
        var heute = LocalDate.now(clock);
        var m = mitarbeiter.finden(person, heute).orElseThrow(ErweitertePersonaldatenService::fehlt);
        var basis = jdbc.sql("SELECT wochenstunden, version FROM vollzeit_einstellung WHERE id = 1").query(Vollzeitbasis.class).single();
        boolean bekannt = m.historieBekannt() && m.datenGueltigAb() != null && !m.datenGueltigAb().isAfter(heute);
        var stunden = bekannt ? m.wochenstunden() : null;
        var wert = stunden == null ? null : stunden.divide(basis.wochenstunden(), 4, RoundingMode.HALF_UP);
        return new Akte(daten(person), new Fte(stunden, basis.wochenstunden(), wert, basis.version(), heute, bekannt));
    }
    @Transactional
    public Akte speichern(@Positive long person, @NotNull @Valid Daten e) {
        // Der Mitarbeiter existiert auch vor der ersten Ergänzung und serialisiert beide Erstschreibvorgänge.
        if (!mitarbeiter.sperren(person)) throw fehlt();
        if (!daten(person).version().equals(e.version())) throw konflikt();
        pruefen(e);
        var neu = e.mitVersion(e.version() + 1);
        jdbc.sql("MERGE INTO personaldaten_erweiterung (mitarbeiter_id, daten, version) KEY(mitarbeiter_id) VALUES (:id, :daten, :version)")
            .param("id", person).param("daten", json.writeValueAsString(neu)).param("version", neu.version()).update();
        return lesen(person);
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Akte vollzeitbasis(@Positive long person, @NotNull @Valid Vollzeitbasis e) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        int geaendert = jdbc.sql("UPDATE vollzeit_einstellung SET wochenstunden = :stunden, version = version + 1 WHERE id = 1 AND version = :version")
            .param("stunden", e.wochenstunden()).param("version", e.version()).update();
        if (geaendert == 0) throw new FachlicherFehler(KONFLIKT, "Die Vollzeitbasis wurde inzwischen geändert. Bitte neu laden und die Eingabe prüfen.");
        return lesen(person);
    }
    private Daten daten(long person) {
        return jdbc.sql("SELECT daten FROM personaldaten_erweiterung WHERE mitarbeiter_id = :id").param("id", person)
            .query(String.class).optional().map(s -> json.readValue(s, Daten.class)).orElseGet(Daten::leer);
    }
    private static void pruefen(Daten e) {
        for (var k : e.kontakte()) if (k.telefon().isBlank() && k.email().isBlank())
            throw ungueltig("Bitte zu jedem Kontakt mindestens Telefon oder E-Mail angeben.");
        for (var v : e.vollmachten()) zeitraum(v.beginn(), v.ende());
        for (var k : e.kostentraeger()) zeitraum(k.beginn(), k.ende());
        for (var b : e.beauftragungen()) zeitraum(b.beginn(), b.ende());
        for (var b : e.bildung()) zeitraum(b.beginn(), b.ende());
        for (var f : e.fortbildungen()) {
            zeitraum(f.beginn(), f.ende()); ZeitraumRegeln.kalenderjahr(f.gueltigBis());
            LocalDate ab = f.ende() == null ? f.beginn() : f.ende();
            if (ab != null && f.gueltigBis() != null && f.gueltigBis().isBefore(ab))
                throw ungueltig("Der Fortbildungsnachweis darf nicht vor dem Fortbildungszeitraum ablaufen.");
        }
        for (var f : e.dienstwagen()) zeitraum(f.beginn(), f.ende());
        for (var f : e.dienstraeder()) zeitraum(f.beginn(), f.ende());
    }
    private static void zeitraum(LocalDate von, LocalDate bis) {
        ZeitraumRegeln.kalenderjahr(von); ZeitraumRegeln.kalenderjahr(bis);
        if (von != null && bis != null && bis.isBefore(von)) throw ungueltig("Das Ende darf nicht vor dem Beginn liegen.");
    }
    private static FachlicherFehler ungueltig(String text) { return new FachlicherFehler(UNGUELTIG, text); }
    private static FachlicherFehler fehlt() { return new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter nicht gefunden."); }
    private static FachlicherFehler konflikt() { return new FachlicherFehler(KONFLIKT, "Die erweiterten Personaldaten wurden inzwischen geändert. Bitte neu laden und die Eingaben prüfen."); }
}
