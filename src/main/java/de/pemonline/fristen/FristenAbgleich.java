package de.pemonline.fristen;

import de.pemonline.benachrichtigung.PersonalhinweisService;
import de.pemonline.personaldaten.ErweiterungsReferenzen;
import de.pemonline.personaldaten.ErweitertePersonaldatenModel.Daten;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Interner mandantenbezogener Abgleich, auch ohne angemeldeten Benutzer ausführbar. */
@Service
public class FristenAbgleich {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ErweiterungsReferenzen referenzen;
    private final PersonalhinweisService hinweise;
    private final Clock clock;
    public FristenAbgleich(JdbcClient jdbc, ObjectMapper json, ErweiterungsReferenzen referenzen, PersonalhinweisService hinweise, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.referenzen = referenzen; this.hinweise = hinweise; this.clock = clock;
    }
    private record Quelle(long person, String art, String id, String titel, LocalDate ablauf, String ziel) {}
    private record Vorhanden(long id, LocalDate ablauf, boolean aktiv) {}
    @Transactional
    public void aktualisieren() {
        synchronisieren();
        erinnern();
    }
    @Transactional
    public void synchronisieren() {
        jdbc.sql("SELECT id FROM fristen_abgleich_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
        var quellen = new ArrayList<Quelle>();
        var personen = jdbc.sql("""
            SELECT mitarbeiter_id FROM personaldaten_erweiterung
            UNION SELECT mitarbeiter_id FROM mitarbeiter_kenntnis WHERE gueltig_bis IS NOT NULL
            ORDER BY mitarbeiter_id
            """).query(Long.class).list();
        for (long person : personen) {
            // Wie alle Lösch-/Personaldaten-Schreibwege zuerst Mitarbeiter, danach Kindzeilen sperren.
            // Eine zwischen Identifikation und Sperre entfernte Person wird einfach übersprungen.
            if (jdbc.sql("SELECT id FROM mitarbeiter WHERE id = :id FOR UPDATE").param("id", person).query(Long.class).optional().isEmpty()) continue;
            quellen.addAll(jdbc.sql("""
                SELECT n.id, n.mitarbeiter_id, k.name, n.gueltig_bis FROM mitarbeiter_kenntnis n
                JOIN kenntnis k ON k.id = n.kenntnis_id WHERE n.gueltig_bis IS NOT NULL AND n.mitarbeiter_id = :person
                """).param("person", person).query((rs, n) -> new Quelle(rs.getLong("mitarbeiter_id"), "QUALIFIKATION", rs.getString("id"), rs.getString("name"),
                    rs.getObject("gueltig_bis", LocalDate.class), "/kenntnisse?mitarbeiterId=" + person + "&nachweisId=" + rs.getLong("id"))).list());
            referenzen.ergaenzen(person);
            var gespeichert = jdbc.sql("SELECT daten FROM personaldaten_erweiterung WHERE mitarbeiter_id = :id").param("id", person).query(String.class).optional();
            if (gespeichert.isEmpty()) continue;
            var d = json.readValue(gespeichert.get(), Daten.class);
            d.vollmachten().forEach(v -> ergaenzen(quellen, person, "VOLLMACHT", "vollmachten", v.id(), v.bezeichnung(), v.ende()));
            d.beauftragungen().forEach(v -> ergaenzen(quellen, person, "BEAUFTRAGUNG", "beauftragungen", v.id(), v.aufgabe(), v.ende()));
            d.fortbildungen().forEach(v -> ergaenzen(quellen, person, "FORTBILDUNG", "fortbildungen", v.id(), v.titel(), v.gueltigBis()));
        }
        var gesehen = new HashSet<Long>();
        for (var q : quellen) {
            var alt = jdbc.sql("SELECT id, ablauf, aktiv FROM personalfrist WHERE mitarbeiter_id = :person AND art = :art AND quelle = :quelle")
                .param("person", q.person()).param("art", q.art()).param("quelle", q.id()).query(Vorhanden.class).optional();
            if (alt.isEmpty()) {
                jdbc.sql("INSERT INTO personalfrist (mitarbeiter_id, art, quelle, titel, ablauf, ziel) VALUES (:person, :art, :quelle, :titel, :ablauf, :ziel)")
                    .param("person", q.person()).param("art", q.art()).param("quelle", q.id()).param("titel", q.titel()).param("ablauf", q.ablauf()).param("ziel", q.ziel()).update();
                gesehen.add(jdbc.sql("SELECT id FROM personalfrist WHERE mitarbeiter_id = :person AND art = :art AND quelle = :quelle")
                    .param("person", q.person()).param("art", q.art()).param("quelle", q.id()).query(Long.class).single());
            } else {
                var a = alt.get(); gesehen.add(a.id());
                boolean neu = !a.ablauf().equals(q.ablauf()) || !a.aktiv();
                jdbc.sql("""
                    UPDATE personalfrist SET titel = :titel, ziel = :ziel, ablauf = :ablauf, aktiv = TRUE,
                    erledigt = CASE WHEN :neu THEN FALSE ELSE erledigt END,
                    wiedervorlage = CASE WHEN :neu THEN NULL ELSE wiedervorlage END,
                    version = CASE WHEN :neu THEN version + 1 ELSE version END WHERE id = :id
                    """).param("id", a.id()).param("titel", q.titel()).param("ziel", q.ziel()).param("ablauf", q.ablauf()).param("neu", neu).update();
            }
        }
        for (long id : jdbc.sql("SELECT id FROM personalfrist WHERE aktiv = TRUE").query(Long.class).list()) if (!gesehen.contains(id))
            jdbc.sql("UPDATE personalfrist SET aktiv = FALSE, version = version + 1 WHERE id = :id").param("id", id).update();
    }
    private static void ergaenzen(List<Quelle> liste, long person, String art, String bereich, UUID id, String titel, LocalDate ablauf) {
        if (ablauf != null) liste.add(new Quelle(person, art, id.toString(), titel, ablauf,
            "/mitarbeiter?personaldatenId=" + person + "&erweiterung=" + bereich + "&datensatz=" + id));
    }
    @Transactional
    public void erinnern() {
        var heute = LocalDate.now(clock);
        var fristen = jdbc.sql("""
            SELECT f.*, m.name AS person FROM personalfrist f JOIN mitarbeiter m ON m.id = f.mitarbeiter_id
            JOIN users u ON u.username = f.zustaendig JOIN authorities a ON a.username = u.username
            JOIN VERWALTUNG.konto k ON k.username = u.username
            WHERE f.aktiv = TRUE AND f.erledigt = FALSE AND u.enabled = TRUE AND k.enabled = TRUE
            AND a.authority IN ('ROLE_PERSONAL', 'ROLE_ADMIN')
            """).query(FristenModel.Frist.class).list();
        for (var f : fristen) {
            var termin = f.wiedervorlage() == null ? f.ablauf().minusDays(f.vorlaufTage()) : f.wiedervorlage();
            if (!termin.isAfter(heute)) hinweise.senden(f.zustaendig(), "frist:" + f.id() + ":" + f.ablauf() + ":" + (f.wiedervorlage() == null ? "ablauf" : f.wiedervorlage()),
                f.person() + ": " + f.titel() + " – Ablauf " + f.ablauf(), f.ziel());
        }
    }
}
