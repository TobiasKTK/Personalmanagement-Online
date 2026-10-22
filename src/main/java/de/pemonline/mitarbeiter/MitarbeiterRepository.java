package de.pemonline.mitarbeiter;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MitarbeiterRepository {
    private static final java.util.Map<String, String> SORTIERUNG = java.util.Map.of(
            "nachname", "LOWER(m.nachname)", "personalnummer", "m.personalnummer",
            "eintrittsdatum", "m.eintrittsdatum", "wochenstunden", "COALESCE(h.wochenstunden, m.wochenstunden)",
            "organisationseinheit", "LOWER(o.name)");
    private static final String SUCHBEDINGUNG = """
            WHERE LOCATE(LOWER(:suche), LOWER(CONCAT_WS(' ', m.personalnummer, m.name,
                m.titel, m.geburtsname, m.kuerzel, o.name, m.stelle,
                m.beschaeftigungsart))) > 0
            AND (:organisation IS NULL OR CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END = :organisation)
            AND (:beschaeftigung = 'ALLE'
              OR (:beschaeftigung = 'KUENFTIG' AND m.eintrittsdatum > :stichtag)
              OR (:beschaeftigung = 'AKTIV' AND m.eintrittsdatum <= :stichtag AND (m.austrittsdatum IS NULL OR m.austrittsdatum >= :stichtag))
              OR (:beschaeftigung = 'AUSGESCHIEDEN' AND m.austrittsdatum < :stichtag))
            """;
    private static final String FROM = """
        FROM mitarbeiter m LEFT JOIN mitarbeiter_historie h ON h.mitarbeiter_id = m.id
          AND h.gueltig_ab = COALESCE((SELECT MAX(h2.gueltig_ab) FROM mitarbeiter_historie h2 WHERE h2.mitarbeiter_id = m.id AND h2.gueltig_ab <= :stichtag),
                                     (SELECT MIN(h3.gueltig_ab) FROM mitarbeiter_historie h3 WHERE h3.mitarbeiter_id = m.id))
        LEFT JOIN organisationseinheit o ON o.id = CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END
        """;
    private static final String SELECT = """
            SELECT m.id, m.name, COALESCE(h.wochenstunden, m.wochenstunden) AS wochenstunden,
            m.eintrittsdatum, m.version, m.personalnummer, m.titel, m.nachname, m.vorname, m.geburtsname, m.kuerzel,
            m.geburtsdatum, m.austrittsdatum, m.stelle, m.beschaeftigungsart,
            CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END AS organisationseinheit_id,
            COALESCE(o.name, '') AS organisationseinheit,
            CASE WHEN h.gueltig_ab <= :stichtag OR (m.eintrittsdatum > :stichtag AND h.gueltig_ab = m.eintrittsdatum) THEN TRUE ELSE FALSE END AS historie_bekannt,
            h.gueltig_ab AS daten_gueltig_ab,
            m.beschaeftigungsstatus AS alt_beschaeftigungsstatus, m.abwesenheitsstatus AS alt_abwesenheitsstatus,
            CASE WHEN m.eintrittsdatum > :stichtag THEN 'KUENFTIG'
                 WHEN m.austrittsdatum < :stichtag THEN 'AUSGESCHIEDEN' ELSE 'AKTIV' END AS beschaeftigung,
            COALESCE((SELECT a.art FROM abwesenheit a
                WHERE a.mitarbeiter_id = m.id AND a.beginn <= :stichtag AND a.ende >= :stichtag
                ORDER BY a.id FETCH FIRST 1 ROW ONLY), '') AS abwesenheit_am_stichtag,
            CAST(:stichtag AS DATE) AS stichtag
            """;
    private final JdbcClient jdbc;

    public MitarbeiterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Mitarbeiter> suchen(MitarbeiterFilter filter, int seite, int groesse) {
        return filter(jdbc.sql(SELECT + FROM + SUCHBEDINGUNG + " ORDER BY " + sortierung(filter) + " LIMIT :limit OFFSET :offset"), filter)
                .param("limit", groesse).param("offset", (long) seite * groesse)
                .query(Mitarbeiter.class).list();
    }

    public long zaehlen(MitarbeiterFilter filter) {
        return filter(jdbc.sql("SELECT COUNT(*)" + FROM + SUCHBEDINGUNG), filter).query(Long.class).single();
    }

    public Optional<Mitarbeiter> finden(long id, java.time.LocalDate stichtag) {
        return jdbc.sql(SELECT + FROM + " WHERE m.id = :id")
                .param("id", id).param("stichtag", stichtag).query(Mitarbeiter.class).optional();
    }

    public long naechstePersonalnummer(boolean sperren) {
        return jdbc.sql("SELECT naechste_nummer FROM personalnummer_zaehler WHERE id = 1"
                + (sperren ? " FOR UPDATE" : "")).query(Long.class).single();
    }

    public void personalnummerVorruecken(long naechste) {
        jdbc.sql("UPDATE personalnummer_zaehler SET naechste_nummer = :naechste WHERE id = 1")
                .param("naechste", naechste).update();
    }

    public long anlegen(MitarbeiterEingabe eingabe, long personalnummer, Long organisationId) {
        try {
        var schluessel = new GeneratedKeyHolder();
        mitParametern(jdbc.sql("""
                INSERT INTO mitarbeiter (name, personalnummer, titel, nachname, vorname, geburtsname,
                    kuerzel, geburtsdatum, eintrittsdatum, austrittsdatum, organisationseinheit_id,
                    stelle, beschaeftigungsart, wochenstunden)
                VALUES (:name, :personalnummer, :titel, :nachname, :vorname, :geburtsname,
                    :kuerzel, :geburtsdatum, :eintrittsdatum, :austrittsdatum, :organisationseinheit,
                    :stelle, :beschaeftigungsart, :wochenstunden)
                """), eingabe).param("organisationseinheit", organisationId, Types.BIGINT).param("personalnummer", personalnummer).update(schluessel, "id");
        return schluessel.getKey().longValue();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            String meldung = "Diese Personalnummer ist bereits vergeben.";
            throw new de.pemonline.fachlich.FachlicherFehler(
                de.pemonline.fachlich.FachlicherFehler.Art.KONFLIKT, meldung, java.util.Map.of("personalnummer", meldung));
        }
    }

    public boolean aendern(long id, MitarbeiterEingabe eingabe, Long organisationId) {
        return mitParametern(jdbc.sql("""
                UPDATE mitarbeiter SET name = :name, titel = :titel,
                    nachname = :nachname, vorname = :vorname, geburtsname = :geburtsname, kuerzel = :kuerzel,
                    geburtsdatum = :geburtsdatum, eintrittsdatum = :eintrittsdatum, austrittsdatum = :austrittsdatum,
                    organisationseinheit_id = :organisationseinheit, stelle = :stelle,
                    beschaeftigungsart = :beschaeftigungsart,
                    wochenstunden = :wochenstunden, version = version + 1
                WHERE id = :id AND version = :version
                """), eingabe).param("organisationseinheit", organisationId, Types.BIGINT).param("id", id).param("version", eingabe.version()).update() == 1;
    }

    private JdbcClient.StatementSpec mitParametern(JdbcClient.StatementSpec sql, MitarbeiterEingabe eingabe) {
        return sql.param("name", eingabe.name())
                .param("titel", eingabe.titel()).param("nachname", eingabe.nachname()).param("vorname", eingabe.vorname())
                .param("geburtsname", eingabe.geburtsname()).param("kuerzel", eingabe.kuerzel())
                .param("geburtsdatum", eingabe.geburtsdatum(), Types.DATE)
                .param("eintrittsdatum", eingabe.eintrittsdatum(), Types.DATE)
                .param("austrittsdatum", eingabe.austrittsdatum(), Types.DATE)
                .param("stelle", eingabe.stelle())
                .param("beschaeftigungsart", eingabe.beschaeftigungsart()).param("wochenstunden", eingabe.wochenstunden());
    }

    public boolean loeschen(long id, long version) {
        return jdbc.sql("DELETE FROM mitarbeiter WHERE id = :id AND version = :version")
                .param("id", id).param("version", version).update() == 1;
    }

    public Statistik statistik(MitarbeiterFilter filter) {
        return filter(jdbc.sql("""
                SELECT COUNT(*) AS anzahl, COALESCE(SUM(CASE WHEN h.gueltig_ab <= :stichtag OR (m.eintrittsdatum > :stichtag AND h.gueltig_ab = m.eintrittsdatum) THEN COALESCE(h.wochenstunden, m.wochenstunden) ELSE 0 END), 0) AS wochenstunden,
                COUNT(CASE WHEN h.id IS NULL OR (h.gueltig_ab > :stichtag AND NOT (m.eintrittsdatum > :stichtag AND h.gueltig_ab = m.eintrittsdatum)) THEN 1 END) AS historie_unbekannt,
                COUNT(CASE WHEN m.eintrittsdatum > :stichtag THEN 1 END) AS bevorstehende_eintritte,
                COUNT(CASE WHEN m.eintrittsdatum <= :stichtag AND (m.austrittsdatum IS NULL OR m.austrittsdatum >= :stichtag) THEN 1 END) AS aktiv,
                COUNT(CASE WHEN m.austrittsdatum < :stichtag THEN 1 END) AS ausgeschieden,
                CAST(:stichtag AS DATE) AS stichtag
                """ + FROM + SUCHBEDINGUNG), filter).query(Statistik.class).single();
    }
    private JdbcClient.StatementSpec filter(JdbcClient.StatementSpec sql, MitarbeiterFilter f) {
        return sql.param("suche", f.suche()).param("organisation", f.organisationseinheitId(), Types.BIGINT)
                .param("beschaeftigung", f.beschaeftigung()).param("stichtag", f.stichtag(), Types.DATE);
    }
    public boolean sperren(long id) {
        return jdbc.sql("SELECT id FROM mitarbeiter WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional().isPresent();
    }
    private String sortierung(MitarbeiterFilter f) {
        return SORTIERUNG.get(f.sortierung()) + " " + f.richtung() + " NULLS LAST, LOWER(m.vorname) " + f.richtung() + ", m.id " + f.richtung();
    }
}
