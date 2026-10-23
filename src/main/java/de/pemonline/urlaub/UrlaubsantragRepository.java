package de.pemonline.urlaub;

import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class UrlaubsantragRepository {
    private final JdbcClient jdbc;
    private final Clock clock;
    public UrlaubsantragRepository(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    private static final String SELECT = "SELECT a.*, m.name FROM urlaubsantrag a JOIN mitarbeiter m ON m.id = a.mitarbeiter_id ";
    public Optional<UrlaubZuordnung> zuordnung(long person) {
        return jdbc.sql("SELECT * FROM urlaub_zuordnung WHERE mitarbeiter_id = :id").param("id", person).query(UrlaubZuordnung.class).optional();
    }
    public Optional<UrlaubZuordnung> eigenesProfil(String name) {
        return jdbc.sql("SELECT * FROM urlaub_zuordnung WHERE konto = :name").param("name", name).query(UrlaubZuordnung.class).optional();
    }
    public boolean aktivesKonto(String name, boolean genehmiger) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM users u JOIN authorities a ON a.username = u.username
                JOIN VERWALTUNG.konto k ON k.username = u.username
                WHERE u.username = :name AND u.enabled = TRUE AND k.enabled = TRUE
                """
                + (genehmiger ? " AND a.authority IN ('ROLE_PERSONAL', 'ROLE_ADMIN')" : ""))
                .param("name", name).query(Long.class).single() > 0;
    }
    public boolean zuordnen(long person, UrlaubZuordnung.Eingabe e) {
        var sql = jdbc.sql(e.version() == null
                ? "INSERT INTO urlaub_zuordnung (mitarbeiter_id, konto, modus, genehmiger) VALUES (:id, :konto, :modus, :genehmiger)"
                : "UPDATE urlaub_zuordnung SET konto = :konto, modus = :modus, genehmiger = :genehmiger, version = version + 1 WHERE mitarbeiter_id = :id AND version = :version")
                .param("id", person).param("konto", e.konto(), Types.VARCHAR).param("modus", e.modus()).param("genehmiger", e.genehmiger(), Types.VARCHAR);
        if (e.version() != null) sql = sql.param("version", e.version());
        return sql.update() == 1;
    }
    public Optional<Urlaubsantrag> finden(long id) { return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query(Urlaubsantrag.class).optional(); }
    public List<Urlaubsantrag> offen(long person, int jahr) {
        return jdbc.sql(SELECT + " WHERE a.mitarbeiter_id = :person AND a.status = 'BEANTRAGT' AND a.beginn <= :ende AND a.ende >= :beginn")
            .param("person", person).param("beginn", LocalDate.of(jahr, 1, 1)).param("ende", LocalDate.of(jahr, 12, 31)).query(Urlaubsantrag.class).list();
    }
    public boolean ueberschneidung(long person, LocalDate beginn, LocalDate ende) {
        return jdbc.sql("SELECT COUNT(*) FROM urlaubsantrag WHERE mitarbeiter_id = :person AND status = 'BEANTRAGT' AND beginn <= :ende AND ende >= :beginn")
            .param("person", person).param("beginn", beginn).param("ende", ende).query(Long.class).single() > 0;
    }
    public boolean gebundeneAbwesenheit(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM urlaubsantrag WHERE abwesenheit_id = :id AND status = 'GENEHMIGT'").param("id", id).query(Long.class).single() > 0;
    }
    public Optional<Urlaubsantrag> genehmigtFuerAbwesenheit(long id) {
        return jdbc.sql(SELECT + " WHERE a.abwesenheit_id = :id AND a.status = 'GENEHMIGT'")
            .param("id", id).query(Urlaubsantrag.class).optional();
    }
    public boolean kuerzen(Urlaubsantrag antrag, de.pemonline.abwesenheit.Abwesenheit rest) {
        return jdbc.sql("UPDATE urlaubsantrag SET beginn = :beginn, ende = :ende, version = version + 1 WHERE id = :id AND version = :version AND status = 'GENEHMIGT'")
            .param("beginn", rest.beginn()).param("ende", rest.ende()).param("id", antrag.id()).param("version", antrag.version()).update() == 1;
    }
    public long restAnlegen(Urlaubsantrag ursprung, de.pemonline.abwesenheit.Abwesenheit rest) {
        var key = new GeneratedKeyHolder();
        // Die zweite Buchung behält Antragsteller, Notiz und ursprüngliche Freigabe; es ist kein neuer Antrag zur Prüfung.
        jdbc.sql("""
            INSERT INTO urlaubsantrag (mitarbeiter_id, antragsteller, beginn, ende, halber_tag, status, notiz,
                entscheidung, bearbeiter, erstellt, entschieden, abwesenheit_id)
            SELECT mitarbeiter_id, antragsteller, :beginn, :ende, halber_tag, 'GENEHMIGT', notiz,
                entscheidung, bearbeiter, erstellt, entschieden, :abwesenheit FROM urlaubsantrag WHERE id = :id
            """).param("beginn", rest.beginn()).param("ende", rest.ende()).param("abwesenheit", rest.id())
            .param("id", ursprung.id()).update(key, "id");
        return key.getKey().longValue();
    }
    public boolean teamGenehmiger(String name, long person) {
        return jdbc.sql("SELECT COUNT(*) FROM users u JOIN authorities r ON r.username=u.username JOIN VERWALTUNG.konto k ON k.username=u.username WHERE u.username=:konto AND u.enabled=TRUE AND k.enabled=TRUE AND r.authority='ROLE_TEAMLEITUNG' AND "
                + de.pemonline.sicherheit.TeamService.zustandigSql(":person", "u.username"))
            .param("konto", name).param("person", person).param("heute", LocalDate.now(clock)).query(Long.class).single() > 0;
    }
    /** Gemeinsame Regel für Listen, Einzelzugriff, Entscheidungen und Benachrichtigungen. */
    public static String genehmigerSql(String person, String antragsteller) {
        return """
            SELECT DISTINCT u.username FROM users u JOIN authorities r ON r.username = u.username
            JOIN VERWALTUNG.konto k ON k.username = u.username
            JOIN urlaub_zuordnung z ON z.mitarbeiter_id = %s
            WHERE u.enabled = TRUE AND k.enabled = TRUE
            AND (z.konto IS NULL OR u.username <> z.konto) AND u.username <> %s
            AND ((r.authority IN ('ROLE_PERSONAL', 'ROLE_ADMIN')
              AND (z.modus = 'ALLE' OR z.genehmiger = u.username OR EXISTS (
                SELECT 1 FROM genehmiger_vertretung v WHERE v.genehmiger = z.genehmiger AND v.vertreter = u.username AND v.beginn <= :heute AND v.ende >= :heute)))
              OR (r.authority = 'ROLE_TEAMLEITUNG' AND (z.modus = 'ALLE' OR z.genehmiger = u.username)
                AND %s))
            """.formatted(person, antragsteller, de.pemonline.sicherheit.TeamService.zustandigSql("z.mitarbeiter_id", "u.username"));
    }
    public List<String> genehmiger(long person, String antragsteller) {
        return jdbc.sql(genehmigerSql(":person", ":antragsteller")).param("person", person).param("antragsteller", antragsteller)
            .param("heute", LocalDate.now(clock)).query(String.class).list();
    }
    public record Seite(List<Urlaubsantrag> eintraege, long gesamt) {}
    public Seite liste(String konto, boolean personal, boolean freigaben, String status, int seite) {
        String where = """
            JOIN urlaub_zuordnung z ON z.mitarbeiter_id = a.mitarbeiter_id
            WHERE (:status = 'ALLE' OR a.status = :status) AND
            """ + (freigaben
            ? " :personal = TRUE AND :konto IN (" + genehmigerSql("a.mitarbeiter_id", "a.antragsteller") + ") "
            : " z.konto = :konto ");
        var liste = listenParameter(jdbc.sql(SELECT + where + " ORDER BY a.erstellt DESC, a.id DESC LIMIT 20 OFFSET :offset"), konto, personal, freigaben, status)
                .param("offset", (long) seite * 20).query(Urlaubsantrag.class).list();
        long gesamt = listenParameter(jdbc.sql("SELECT COUNT(*) FROM urlaubsantrag a JOIN mitarbeiter m ON m.id = a.mitarbeiter_id " + where), konto, personal, freigaben, status).query(Long.class).single();
        return new Seite(liste, gesamt);
    }
    private JdbcClient.StatementSpec listenParameter(JdbcClient.StatementSpec sql, String konto, boolean personal, boolean freigaben, String status) {
        sql = sql.param("konto", konto).param("status", status);
        return freigaben ? sql.param("personal", personal).param("heute", LocalDate.now(clock)) : sql;
    }
    public long anlegen(long person, String konto, Urlaubsantrag.Eingabe e, OffsetDateTime zeit) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO urlaubsantrag (mitarbeiter_id, antragsteller, beginn, ende, halber_tag, status, notiz, erstellt) VALUES (:person, :konto, :beginn, :ende, :halb, 'BEANTRAGT', :notiz, :zeit)")
            .param("person", person).param("konto", konto).param("beginn", e.beginn()).param("ende", e.ende()).param("halb", e.halberTag())
            .param("notiz", e.notiz() == null ? "" : e.notiz().strip()).param("zeit", zeit).update(key, "id");
        return key.getKey().longValue();
    }
    public boolean entscheiden(Urlaubsantrag a, String status, String bearbeiter, String grund, OffsetDateTime zeit, Long abwesenheit) {
        return jdbc.sql("UPDATE urlaubsantrag SET status = :status, bearbeiter = :bearbeiter, entscheidung = :grund, entschieden = :zeit, abwesenheit_id = :abwesenheit, version = version + 1 WHERE id = :id AND version = :version")
            .param("status", status).param("bearbeiter", bearbeiter).param("grund", grund == null ? "" : grund.strip()).param("zeit", zeit)
            .param("abwesenheit", abwesenheit, Types.BIGINT).param("id", a.id()).param("version", a.version()).update() == 1;
    }
}
