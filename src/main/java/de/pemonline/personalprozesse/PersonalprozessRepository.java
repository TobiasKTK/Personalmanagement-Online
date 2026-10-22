package de.pemonline.personalprozesse;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static de.pemonline.personalprozesse.PersonalprozessModel.*;

@Repository
public class PersonalprozessRepository {
    private final JdbcClient jdbc;
    private static final String FROM = " FROM personalprozess_aufgabe a JOIN personalprozess_vorgang p ON p.id = a.vorgang_id JOIN mitarbeiter m ON m.id = p.mitarbeiter_id ";
    private static final String SELECT = "SELECT a.*, p.mitarbeiter_id, m.name AS mitarbeiter_name, p.typ, p.titel AS vorgang_titel " + FROM;
    public PersonalprozessRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private record Vorlagenkopf(long id, String typ, String titel, long version) {}
    private Vorlage aufgabenErgaenzen(Vorlagenkopf kopf) {
        var aufgaben = jdbc.sql("SELECT titel, tage FROM personalprozess_vorlagenaufgabe WHERE vorlage_id = :id ORDER BY position")
                .param("id", kopf.id()).query(Vorlagenaufgabe.class).list();
        return new Vorlage(kopf.id(), kopf.typ(), kopf.titel(), kopf.version(), aufgaben);
    }
    public List<Vorlage> vorlagen() {
        return jdbc.sql("SELECT * FROM personalprozess_vorlage ORDER BY typ, titel, id").query(Vorlagenkopf.class).list()
                .stream().map(this::aufgabenErgaenzen).toList();
    }
    public Optional<Vorlage> vorlage(long id, boolean sperren) {
        return jdbc.sql("SELECT * FROM personalprozess_vorlage WHERE id = :id" + (sperren ? " FOR UPDATE" : ""))
                .param("id", id).query(Vorlagenkopf.class).optional().map(this::aufgabenErgaenzen);
    }
    public long neueVorlage(VorlagenEingabe e) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO personalprozess_vorlage (typ, titel) VALUES (:typ, :titel)")
                .param("typ", e.typ()).param("titel", e.titel().strip()).update(key, "id");
        long id = key.getKey().longValue();
        vorlagenaufgaben(id, e.aufgaben()); return id;
    }
    public boolean vorlageAendern(long id, VorlagenEingabe e) {
        int aktualisiert = jdbc.sql("UPDATE personalprozess_vorlage SET typ = :typ, titel = :titel, version = version + 1 WHERE id = :id AND version = :version")
                .param("typ", e.typ()).param("titel", e.titel().strip()).param("id", id).param("version", e.version()).update();
        if (aktualisiert == 0) return false;
        jdbc.sql("DELETE FROM personalprozess_vorlagenaufgabe WHERE vorlage_id = :id").param("id", id).update();
        vorlagenaufgaben(id, e.aufgaben()); return true;
    }
    private void vorlagenaufgaben(long id, List<Vorlagenaufgabe> aufgaben) {
        for (int i = 0; i < aufgaben.size(); i++) {
            var a = aufgaben.get(i);
            jdbc.sql("INSERT INTO personalprozess_vorlagenaufgabe (vorlage_id, position, titel, tage) VALUES (:id, :position, :titel, :tage)")
                    .param("id", id).param("position", i).param("titel", a.titel().strip()).param("tage", a.tage()).update();
        }
    }
    public List<String> konten() {
        return jdbc.sql("""
                SELECT u.username FROM users u JOIN VERWALTUNG.konto k ON k.username = u.username
                WHERE u.enabled = TRUE AND k.enabled = TRUE AND EXISTS
                (SELECT 1 FROM authorities r WHERE r.username = u.username AND r.authority IN ('ROLE_ADMIN', 'ROLE_PERSONAL', 'ROLE_LESEN'))
                ORDER BY u.username
                """).query(String.class).list();
    }
    public long vorgang(Vorlage v, Start e, String konto, OffsetDateTime jetzt) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO personalprozess_vorgang (mitarbeiter_id, typ, titel, bezugsdatum, erstellt_von, erstellt)
                VALUES (:person, :typ, :titel, :datum, :konto, :jetzt)
                """).param("person", e.mitarbeiterId()).param("typ", v.typ()).param("titel", v.titel())
                .param("datum", e.bezugsdatum()).param("konto", konto).param("jetzt", jetzt).update(key, "id");
        return key.getKey().longValue();
    }
    public long aufgabe(long vorgang, Vorlagenaufgabe a, String konto, LocalDate faellig) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO personalprozess_aufgabe (vorgang_id, titel, verantwortlich, faellig_am) VALUES (:vorgang, :titel, :konto, :datum)")
                .param("vorgang", vorgang).param("titel", a.titel()).param("konto", konto).param("datum", faellig).update(key, "id");
        return key.getKey().longValue();
    }
    public Optional<Aufgabe> aufgabe(long id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query(Aufgabe.class).optional();
    }
    public Seite aufgaben(String konto, boolean personal, String status, int seite) {
        String where = " WHERE (:personal = TRUE OR a.verantwortlich = :konto) AND (:status = 'ALLE' OR a.status = :status) ";
        var eintraege = jdbc.sql(SELECT + where + " ORDER BY CASE WHEN a.status = 'ERLEDIGT' THEN 1 ELSE 0 END, a.faellig_am, a.id LIMIT 20 OFFSET :offset")
                .param("personal", personal).param("konto", konto).param("status", status).param("offset", (long) seite * 20).query(Aufgabe.class).list();
        long gesamt = jdbc.sql("SELECT COUNT(*)" + FROM + where).param("personal", personal).param("konto", konto).param("status", status).query(Long.class).single();
        return new Seite(eintraege, gesamt);
    }
    public boolean status(long id, Status e) {
        return jdbc.sql("UPDATE personalprozess_aufgabe SET status = :status, version = version + 1 WHERE id = :id AND version = :version")
                .param("status", e.status()).param("id", id).param("version", e.version()).update() == 1;
    }
    public boolean zuordnen(long id, Zuordnung e) {
        return jdbc.sql("UPDATE personalprozess_aufgabe SET verantwortlich = :konto, faellig_am = :datum, version = version + 1 WHERE id = :id AND version = :version")
                .param("konto", e.verantwortlich()).param("datum", e.faelligAm()).param("id", id).param("version", e.version()).update() == 1;
    }
}
