package de.pemonline.mitarbeiter;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MitarbeiterRepository {
    private static final String BESCHAEFTIGUNG = """
        CASE WHEN (m.eintrittsdatum <= :stichtag AND (m.austrittsdatum IS NULL OR m.austrittsdatum >= :stichtag))
                  OR EXISTS (SELECT 1 FROM beschaeftigungszeit b WHERE b.mitarbeiter_id = m.id AND b.beginn <= :stichtag AND b.ende >= :stichtag)
             THEN 'AKTIV'
             WHEN m.austrittsdatum < :stichtag OR EXISTS (SELECT 1 FROM beschaeftigungszeit b WHERE b.mitarbeiter_id = m.id AND b.ende < :stichtag)
             THEN 'AUSGESCHIEDEN' ELSE 'KUENFTIG' END
        """;
    private static final String ABWESENHEIT_AM_STICHTAG = """
            COALESCE((SELECT a.art FROM abwesenheit a
                WHERE a.mitarbeiter_id = m.id AND a.beginn <= :stichtag AND a.ende >= :stichtag
                ORDER BY a.id FETCH FIRST 1 ROW ONLY), '')
            """;
    private static final java.util.Map<String, String> SORTIERUNG = java.util.Map.ofEntries(
            java.util.Map.entry("personalnummer", "m.personalnummer"),
            java.util.Map.entry("titel", "LOWER(m.titel)"),
            java.util.Map.entry("nachname", "LOWER(m.nachname)"),
            java.util.Map.entry("vorname", "LOWER(m.vorname)"),
            java.util.Map.entry("geburtsname", "LOWER(m.geburtsname)"),
            java.util.Map.entry("kuerzel", "LOWER(m.kuerzel)"),
            java.util.Map.entry("geburtsdatum", "m.geburtsdatum"),
            java.util.Map.entry("eintrittsdatum", "eintrittsdatum"),
            java.util.Map.entry("austrittsdatum", "austrittsdatum"),
            java.util.Map.entry("organisationseinheit", "LOWER(o.name)"),
            java.util.Map.entry("stelle", "LOWER(COALESCE(sp.name, m.stelle))"),
            java.util.Map.entry("beschaeftigungsart", "LOWER(m.beschaeftigungsart)"),
            java.util.Map.entry("abwesenheitAmStichtag", "CASE " + ABWESENHEIT_AM_STICHTAG + """
                    WHEN 'ELTERNZEIT' THEN 'Elternzeit' WHEN 'KRANKHEIT' THEN 'Krankheit'
                        WHEN 'SONSTIGES' THEN 'Sonstiges' WHEN 'URLAUB' THEN 'Urlaub' ELSE 'Keine erfasste Abwesenheit' END
                    """),
            java.util.Map.entry("wochenstunden", "COALESCE(h.wochenstunden, m.wochenstunden)"));
    private static final String SUCHBEDINGUNG = """
            WHERE LOCATE(LOWER(:suche), LOWER(CONCAT_WS(' ', m.personalnummer, m.name,
                m.titel, m.geburtsname, m.kuerzel, o.name, COALESCE(sp.name, m.stelle),
                m.beschaeftigungsart))) > 0
            AND (:organisation IS NULL OR CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END = :organisation)
            AND (:beschaeftigung = 'ALLE' OR m.beschaeftigung = :beschaeftigung)
            """;
    private static final String FROM = "FROM (SELECT m.*, " + BESCHAEFTIGUNG + " AS beschaeftigung FROM mitarbeiter m) m " + """
        LEFT JOIN beschaeftigungszeit b ON b.mitarbeiter_id = m.id AND b.beginn <= :stichtag AND b.ende >= :stichtag
        LEFT JOIN mitarbeiter_historie h ON h.mitarbeiter_id = m.id
          AND h.gueltig_ab = COALESCE((SELECT MAX(h2.gueltig_ab) FROM mitarbeiter_historie h2 WHERE h2.mitarbeiter_id = m.id AND h2.gueltig_ab <= :stichtag),
                                     (SELECT MIN(h3.gueltig_ab) FROM mitarbeiter_historie h3 WHERE h3.mitarbeiter_id = m.id))
        LEFT JOIN organisationseinheit o ON o.id = CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END
        LEFT JOIN personaldaten pd ON pd.mitarbeiter_id = m.id
        LEFT JOIN stellenprofil sp ON sp.id = pd.profil_id
        """;
    private static final String SELECT = """
            SELECT m.id, m.name, COALESCE(h.wochenstunden, m.wochenstunden) AS wochenstunden,
            CASE WHEN :historisch THEN COALESCE(b.beginn, m.eintrittsdatum) ELSE m.eintrittsdatum END AS eintrittsdatum,
            m.version, m.personalnummer, m.titel, m.nachname, m.vorname, m.geburtsname, m.kuerzel,
            m.geburtsdatum, CASE WHEN :historisch THEN COALESCE(b.ende, m.austrittsdatum) ELSE m.austrittsdatum END AS austrittsdatum, COALESCE(sp.name, m.stelle) AS stelle, m.beschaeftigungsart,
            CASE WHEN h.id IS NULL THEN m.organisationseinheit_id ELSE h.organisationseinheit_id END AS organisationseinheit_id,
            COALESCE(o.name, '') AS organisationseinheit,
            CASE WHEN h.gueltig_ab <= :stichtag OR (m.eintrittsdatum > :stichtag AND h.gueltig_ab = m.eintrittsdatum) THEN TRUE ELSE FALSE END AS historie_bekannt,
            h.gueltig_ab AS daten_gueltig_ab,
            m.beschaeftigungsstatus AS alt_beschaeftigungsstatus, m.abwesenheitsstatus AS alt_abwesenheitsstatus,
            m.beschaeftigung,
            """ + ABWESENHEIT_AM_STICHTAG + """
            AS abwesenheit_am_stichtag,
            CAST(:stichtag AS DATE) AS stichtag
            """;
    private final JdbcClient jdbc;

    public MitarbeiterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean fruehereBeschaeftigungUeberlappt(long id, java.time.LocalDate eintritt) {
        return jdbc.sql("SELECT COUNT(*) FROM beschaeftigungszeit WHERE mitarbeiter_id = :id AND ende >= :eintritt")
                .param("id", id).param("eintritt", eintritt).query(Long.class).single() > 0;
    }

    public boolean fruehereBeschaeftigungEnthaelt(long id, java.time.LocalDate beginn, java.time.LocalDate ende) {
        return jdbc.sql("SELECT COUNT(*) FROM beschaeftigungszeit WHERE mitarbeiter_id = :id AND beginn <= :beginn AND ende >= :ende")
                .param("id", id).param("beginn", beginn).param("ende", ende).query(Long.class).single() > 0;
    }

    public List<Mitarbeiter> suchen(MitarbeiterFilter filter, int seite, int groesse) {
        return filter(jdbc.sql(SELECT + FROM + SUCHBEDINGUNG + " ORDER BY " + sortierung(filter) + " LIMIT :limit OFFSET :offset"), filter)
                .param("limit", groesse).param("offset", (long) seite * groesse).param("historisch", true)
                .query(Mitarbeiter.class).list();
    }

    public long zaehlen(MitarbeiterFilter filter) {
        return filter(jdbc.sql("SELECT COUNT(*)" + FROM + SUCHBEDINGUNG), filter).query(Long.class).single();
    }

    public Optional<Mitarbeiter> finden(long id, java.time.LocalDate stichtag) {
        return jdbc.sql(SELECT + FROM + " WHERE m.id = :id")
                .param("id", id).param("stichtag", stichtag).param("historisch", false).query(Mitarbeiter.class).optional();
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
                COUNT(CASE WHEN m.beschaeftigung = 'KUENFTIG' THEN 1 END) AS bevorstehende_eintritte,
                COUNT(CASE WHEN m.beschaeftigung = 'AKTIV' THEN 1 END) AS aktiv,
                COUNT(CASE WHEN m.beschaeftigung = 'AUSGESCHIEDEN' THEN 1 END) AS ausgeschieden,
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
