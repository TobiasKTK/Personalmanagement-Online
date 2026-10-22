package de.pemonline.entwicklung;

import static de.pemonline.entwicklung.EntwicklungModel.*;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.fachlich.ZeitraumRegeln;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Alle Tabellen liegen wie die Mitarbeiterdaten im ausgewählten Mandantenschema. */
@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class EntwicklungService {
    private final JdbcClient jdbc;
    public EntwicklungService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Person> personen() {
        return jdbc.sql("SELECT id, name, personalnummer FROM mitarbeiter ORDER BY LOWER(name), id")
                .query(Person.class).list();
    }

    public List<Kenntnis> kenntnisse() {
        return jdbc.sql("SELECT id, name, kategorie, version FROM kenntnis ORDER BY kategorie, name_normalisiert, id")
                .query(Kenntnis.class).list();
    }

    // H2 muss auch erstmals gelesene Tabellen aus demselben Transaktionsstand lesen.
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public List<Profil> profile() {
        var anforderungen = jdbc.sql("SELECT profil_id, kenntnis_id, niveau FROM stellenanforderung ORDER BY kenntnis_id")
                .query((rs, n) -> new ProfilAnforderung(rs.getLong(1), new Anforderung(rs.getLong(2), rs.getInt(3)))).list();
        return jdbc.sql("SELECT * FROM stellenprofil ORDER BY name_normalisiert, id")
                .query((rs, zeilennummer) -> {
                    long profilId = rs.getLong("id");
                    var profilAnforderungen = new ArrayList<Anforderung>();
                    for (var zuordnung : anforderungen) {
                        if (zuordnung.profilId() == profilId) {
                            profilAnforderungen.add(zuordnung.anforderung());
                        }
                    }
                    return new Profil(profilId, rs.getString("name"), rs.getString("beschreibung"),
                            rs.getString("aufgaben"), rs.getLong("version"), List.copyOf(profilAnforderungen));
                }).list();
    }

    private record ProfilAnforderung(long profilId, Anforderung anforderung) {}

    public List<Planstelle> planstellen() {
        return jdbc.sql("SELECT * FROM planstelle ORDER BY LOWER(bezeichnung), id").query(Planstelle.class).list();
    }
    public List<Nachweis> nachweise(@Positive long mitarbeiterId) {
        existenzPruefen("mitarbeiter", mitarbeiterId);
        return jdbc.sql("SELECT * FROM mitarbeiter_kenntnis WHERE mitarbeiter_id = :id ORDER BY kenntnis_id")
                .param("id", mitarbeiterId).query(Nachweis.class).list();
    }
    public List<Ausbildung> ausbildungen() {
        return jdbc.sql("SELECT * FROM ausbildung ORDER BY beginn DESC, id DESC").query(Ausbildung.class).list();
    }
    public List<Station> stationen(@Positive long ausbildungId) {
        existenzPruefen("ausbildung", ausbildungId);
        return jdbc.sql("SELECT * FROM ausbildungsstation WHERE ausbildung_id = :id ORDER BY beginn, id")
                .param("id", ausbildungId).query(Station.class).list();
    }

    @Transactional
    public Kenntnis kenntnisSpeichern(@Positive Long id, @NotNull @Valid KenntnisEingabe eingabe) {
        sperren();
        // Kategorien bestimmen die Interpretation bereits gespeicherter Anforderungen und Nachweise.
        if (id != null) {
            versionPruefen("kenntnis", id, eingabe.version());
            String bisherigeKategorie = jdbc.sql("SELECT kategorie FROM kenntnis WHERE id = :id").param("id", id).query(String.class).single();
            if (!bisherigeKategorie.equals(eingabe.kategorie().name()) && (anzahl("SELECT COUNT(*) FROM stellenanforderung WHERE kenntnis_id = :id", id) > 0
                    || anzahl("SELECT COUNT(*) FROM mitarbeiter_kenntnis WHERE kenntnis_id = :id", id) > 0))
                throw new FachlicherFehler(KONFLIKT, "Die Kategorie einer verwendeten Kenntnis kann nicht geändert werden.");
        }
        var spaltenwerte = new LinkedHashMap<String, Object>();
        spaltenwerte.put("name", eingabe.name());
        spaltenwerte.put("name_normalisiert", nameNormalisieren(eingabe.name()));
        spaltenwerte.put("kategorie", eingabe.kategorie().name());
        long gespeicherteId = speichern("kenntnis", id, eingabe.version(), spaltenwerte);
        return jdbc.sql("SELECT id, name, kategorie, version FROM kenntnis WHERE id = :id").param("id", gespeicherteId).query(Kenntnis.class).single();
    }
    @Transactional
    public Profil profilSpeichern(@Positive Long id, @NotNull @Valid ProfilEingabe eingabe) {
        sperren();
        Set<Long> verwendeteKenntnisIds = new HashSet<>();
        for (var anforderung : eingabe.anforderungen()) {
            existenzPruefen("kenntnis", anforderung.kenntnisId());
            if (!verwendeteKenntnisIds.add(anforderung.kenntnisId())) {
                throw new FachlicherFehler(UNGUELTIG, "Eine Kenntnis darf im Stellenprofil nur einmal vorkommen.");
            }
        }
        var spaltenwerte = new LinkedHashMap<String, Object>();
        spaltenwerte.put("name", eingabe.name());
        spaltenwerte.put("name_normalisiert", nameNormalisieren(eingabe.name()));
        spaltenwerte.put("beschreibung", eingabe.beschreibung());
        spaltenwerte.put("aufgaben", eingabe.aufgaben());
        long gespeicherteId = speichern("stellenprofil", id, eingabe.version(), spaltenwerte);
        jdbc.sql("DELETE FROM stellenanforderung WHERE profil_id = :id").param("id", gespeicherteId).update();
        for (var anforderung : eingabe.anforderungen()) {
            jdbc.sql("INSERT INTO stellenanforderung (profil_id, kenntnis_id, niveau) VALUES (:profil, :kenntnis, :niveau)")
                    .param("profil", gespeicherteId).param("kenntnis", anforderung.kenntnisId())
                    .param("niveau", anforderung.niveau()).update();
        }
        return profile().stream().filter(profil -> profil.id() == gespeicherteId).findFirst().orElseThrow();
    }
    @Transactional
    public Planstelle planstelleSpeichern(@Positive Long id, @NotNull @Valid PlanstellenEingabe eingabe) {
        sperren();
        existenzPruefen("organisationseinheit", eingabe.organisationId());
        existenzPruefen("stellenprofil", eingabe.profilId());
        if (eingabe.mitarbeiterId() != null) {
            existenzPruefen("mitarbeiter", eingabe.mitarbeiterId());
            BigDecimal besetzt = jdbc.sql("SELECT COALESCE(SUM(anteil), 0) FROM planstelle WHERE mitarbeiter_id = :person AND id <> :id")
                    .param("person", eingabe.mitarbeiterId()).param("id", id == null ? -1 : id).query(BigDecimal.class).single();
            if (besetzt.add(eingabe.anteil()).compareTo(BigDecimal.ONE) > 0) {
                throw new FachlicherFehler(KONFLIKT, "Die besetzten Stellenanteile dieser Person dürfen zusammen 1,00 nicht überschreiten.");
            }
        }
        var spaltenwerte = new LinkedHashMap<String, Object>();
        spaltenwerte.put("bezeichnung", eingabe.bezeichnung());
        spaltenwerte.put("organisation_id", eingabe.organisationId());
        spaltenwerte.put("profil_id", eingabe.profilId());
        spaltenwerte.put("anteil", eingabe.anteil());
        spaltenwerte.put("mitarbeiter_id", eingabe.mitarbeiterId());
        long gespeicherteId = speichern("planstelle", id, eingabe.version(), spaltenwerte);
        return jdbc.sql("SELECT * FROM planstelle WHERE id = :id").param("id", gespeicherteId).query(Planstelle.class).single();
    }
    @Transactional
    public Nachweis nachweisSpeichern(@Positive Long id, @NotNull @Valid NachweisEingabe eingabe) {
        sperren();
        existenzPruefen("mitarbeiter", eingabe.mitarbeiterId());
        existenzPruefen("kenntnis", eingabe.kenntnisId());
        zeitraumPruefen(eingabe.erworbenAm(), eingabe.gueltigBis());
        if (id != null) {
            versionPruefen("mitarbeiter_kenntnis", id, eingabe.version());
            var bisherigerNachweis = jdbc.sql("SELECT * FROM mitarbeiter_kenntnis WHERE id = :id").param("id", id).query(Nachweis.class).single();
            if (bisherigerNachweis.mitarbeiterId() != eingabe.mitarbeiterId() || bisherigerNachweis.kenntnisId() != eingabe.kenntnisId()) {
                throw new FachlicherFehler(UNGUELTIG, "Person und Kenntnis eines Nachweises können nicht ausgetauscht werden.");
            }
        }
        var spaltenwerte = new LinkedHashMap<String, Object>();
        spaltenwerte.put("mitarbeiter_id", eingabe.mitarbeiterId());
        spaltenwerte.put("kenntnis_id", eingabe.kenntnisId());
        spaltenwerte.put("niveau", eingabe.niveau());
        spaltenwerte.put("erworben_am", eingabe.erworbenAm());
        spaltenwerte.put("gueltig_bis", eingabe.gueltigBis());
        spaltenwerte.put("nachweis", eingabe.nachweis());
        long gespeicherteId = speichern("mitarbeiter_kenntnis", id, eingabe.version(), spaltenwerte);
        return jdbc.sql("SELECT * FROM mitarbeiter_kenntnis WHERE id = :id").param("id", gespeicherteId).query(Nachweis.class).single();
    }

    /** Ohne Profilangabe gelten die höchsten Anforderungen aller aktuell zugeordneten Planstellen. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public List<Abgleich> abgleich(@Positive long mitarbeiterId, @Positive Long profilId, @NotNull LocalDate stichtag) {
        existenzPruefen("mitarbeiter", mitarbeiterId);
        datumPruefen(stichtag);
        if (profilId != null) {
            existenzPruefen("stellenprofil", profilId);
        }

        var anforderungen = anforderungenErmitteln(mitarbeiterId, profilId);
        var nachweiseNachKenntnis = new HashMap<Long, Nachweis>();
        for (var nachweis : nachweise(mitarbeiterId)) {
            nachweiseNachKenntnis.put(nachweis.kenntnisId(), nachweis);
        }
        var kenntnisseNachId = new HashMap<Long, Kenntnis>();
        for (var kenntnis : kenntnisse()) {
            kenntnisseNachId.put(kenntnis.id(), kenntnis);
        }

        var ergebnisse = new ArrayList<Abgleich>();
        for (var anforderung : anforderungen) {
            var nachweis = nachweiseNachKenntnis.get(anforderung.kenntnisId());
            var kenntnis = kenntnisseNachId.get(anforderung.kenntnisId());
            ergebnisse.add(anforderungVergleichen(anforderung, nachweis, kenntnis, stichtag));
        }
        return List.copyOf(ergebnisse);
    }

    private List<Anforderung> anforderungenErmitteln(long mitarbeiterId, Long profilId) {
        return jdbc.sql("""
            SELECT a.kenntnis_id, MAX(a.niveau) AS niveau FROM stellenanforderung a
            WHERE (:profil IS NOT NULL AND a.profil_id = :profil)
               OR (:profil IS NULL AND a.profil_id IN (SELECT p.profil_id FROM planstelle p WHERE p.mitarbeiter_id = :person))
            GROUP BY a.kenntnis_id ORDER BY a.kenntnis_id
            """).param("profil", profilId, Types.BIGINT).param("person", mitarbeiterId).query(Anforderung.class).list();
    }

    private Abgleich anforderungVergleichen(Anforderung anforderung, Nachweis nachweis,
            Kenntnis kenntnis, LocalDate stichtag) {
        int vorhandenesNiveau = 0;
        String grund;
        if (nachweis == null) {
            grund = "Kein Nachweis";
        } else if (nachweis.erworbenAm().isAfter(stichtag)) {
            grund = "Noch nicht erworben";
        } else if (nachweis.gueltigBis() != null && nachweis.gueltigBis().isBefore(stichtag)) {
            grund = "Nachweis abgelaufen";
        } else {
            vorhandenesNiveau = nachweis.niveau();
            grund = vorhandenesNiveau < anforderung.niveau() ? "Niveau zu niedrig" : "Erfüllt";
        }

        // Ein noch nicht gültiger oder abgelaufener Nachweis deckt keinen Schulungsbedarf ab.
        int fehlendesNiveau = Math.max(0, anforderung.niveau() - vorhandenesNiveau);
        return new Abgleich(kenntnis.id(), kenntnis.name(), kenntnis.kategorie(),
                anforderung.niveau(), vorhandenesNiveau, fehlendesNiveau, grund);
    }
    @Transactional
    public Ausbildung ausbildungSpeichern(@Positive Long id, @NotNull @Valid AusbildungEingabe eingabe) {
        sperren();
        existenzPruefen("mitarbeiter", eingabe.mitarbeiterId());
        zeitraumPruefen(eingabe.beginn(), eingabe.ende());
        if (eingabe.ausbilderId() != null) {
            existenzPruefen("mitarbeiter", eingabe.ausbilderId());
            if (eingabe.ausbilderId() == eingabe.mitarbeiterId()) {
                throw new FachlicherFehler(UNGUELTIG, "Auszubildende können nicht ihre eigenen Ausbilder sein.");
            }
        }
        if (id != null) {
            versionPruefen("ausbildung", id, eingabe.version());
            long person = jdbc.sql("SELECT mitarbeiter_id FROM ausbildung WHERE id = :id").param("id", id).query(Long.class).single();
            if (person != eingabe.mitarbeiterId()) {
                throw new FachlicherFehler(UNGUELTIG, "Die Person eines Ausbildungsverhältnisses kann nicht ausgetauscht werden.");
            }
            if (jdbc.sql("SELECT COUNT(*) FROM ausbildungsstation WHERE ausbildung_id = :id AND (beginn < :beginn OR ende > :ende)")
                    .param("id", id).param("beginn", eingabe.beginn()).param("ende", eingabe.ende()).query(Long.class).single() > 0)
                throw new FachlicherFehler(KONFLIKT, "Der Zeitraum muss alle vorhandenen Ausbildungsstationen umfassen.");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM ausbildung WHERE mitarbeiter_id = :person AND id <> :id AND beginn <= :ende AND ende >= :beginn")
                .param("person", eingabe.mitarbeiterId()).param("id", id == null ? -1 : id).param("beginn", eingabe.beginn()).param("ende", eingabe.ende()).query(Long.class).single() > 0)
            throw new FachlicherFehler(KONFLIKT, "Für diese Person besteht bereits ein Ausbildungsverhältnis in diesem Zeitraum.");
        var spaltenwerte = new LinkedHashMap<String, Object>();
        spaltenwerte.put("mitarbeiter_id", eingabe.mitarbeiterId());
        spaltenwerte.put("beruf", eingabe.beruf());
        spaltenwerte.put("institution", eingabe.institution());
        spaltenwerte.put("ausbilder_id", eingabe.ausbilderId());
        spaltenwerte.put("beginn", eingabe.beginn());
        spaltenwerte.put("ende", eingabe.ende());
        spaltenwerte.put("status", eingabe.status().name());
        long gespeicherteId = speichern("ausbildung", id, eingabe.version(), spaltenwerte);
        return jdbc.sql("SELECT * FROM ausbildung WHERE id = :id").param("id", gespeicherteId).query(Ausbildung.class).single();
    }
    @Transactional
    public Station stationSpeichern(@Positive Long id, @NotNull @Valid StationEingabe eingabe) {
        sperren();
        existenzPruefen("ausbildung", eingabe.ausbildungId());
        zeitraumPruefen(eingabe.beginn(), eingabe.ende());
        if (eingabe.organisationId() != null) {
            existenzPruefen("organisationseinheit", eingabe.organisationId());
        }
        var ausbildung = jdbc.sql("SELECT * FROM ausbildung WHERE id = :id").param("id", eingabe.ausbildungId()).query(Ausbildung.class).single();
        if (eingabe.beginn().isBefore(ausbildung.beginn()) || eingabe.ende().isAfter(ausbildung.ende())) {
            throw new FachlicherFehler(UNGUELTIG, "Die Station muss innerhalb des Ausbildungszeitraums liegen.");
        }
        if (id != null) {
            versionPruefen("ausbildungsstation", id, eingabe.version());
            long bisherigeAusbildungId = jdbc.sql("SELECT ausbildung_id FROM ausbildungsstation WHERE id = :id").param("id", id).query(Long.class).single();
            if (bisherigeAusbildungId != eingabe.ausbildungId()) {
                throw new FachlicherFehler(UNGUELTIG, "Die Station kann nicht in eine andere Ausbildung verschoben werden.");
            }
        }
        // Parallele Stationen sind erlaubt (z. B. Berufsschule und betriebliche Ausbildung).
        var spaltenwerte = new LinkedHashMap<String, Object>();
        spaltenwerte.put("ausbildung_id", eingabe.ausbildungId());
        spaltenwerte.put("titel", eingabe.titel());
        spaltenwerte.put("lernziele", eingabe.lernziele());
        spaltenwerte.put("organisation_id", eingabe.organisationId());
        spaltenwerte.put("beginn", eingabe.beginn());
        spaltenwerte.put("ende", eingabe.ende());
        spaltenwerte.put("status", eingabe.status().name());
        long gespeicherteId = speichern("ausbildungsstation", id, eingabe.version(), spaltenwerte);
        return jdbc.sql("SELECT * FROM ausbildungsstation WHERE id = :id").param("id", gespeicherteId).query(Station.class).single();
    }

    public enum Bereich {
        KENNTNISSE("kenntnis"),
        PROFILE("stellenprofil"),
        PLANSTELLEN("planstelle"),
        NACHWEISE("mitarbeiter_kenntnis"),
        AUSBILDUNGEN("ausbildung"),
        STATIONEN("ausbildungsstation");

        private final String tabelle;

        Bereich(String tabelle) {
            this.tabelle = tabelle;
        }
    }
    @Transactional
    public void loeschen(@NotNull Bereich bereich, @Positive long id, @PositiveOrZero long version) {
        sperren();
        versionPruefen(bereich.tabelle, id, version);
        if (bereich == Bereich.AUSBILDUNGEN && anzahl("SELECT COUNT(*) FROM ausbildungsstation WHERE ausbildung_id = :id", id) > 0) {
            throw new FachlicherFehler(KONFLIKT, "Bitte zuerst die Stationen dieses Ausbildungsplans entfernen.");
        }
        jdbc.sql("DELETE FROM " + bereich.tabelle + " WHERE id = :id").param("id", id).update();
    }

    private void sperren() {
        // Zusammengehörige Prüfungen und Änderungen dürfen sich nicht gegenseitig überholen.
        jdbc.sql("SELECT id FROM entwicklung_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
    }

    private long anzahl(String sql, long id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }
    private void existenzPruefen(String tabelle, long id) {
        if (anzahl("SELECT COUNT(*) FROM " + tabelle + " WHERE id = :id", id) == 0) {
            throw new FachlicherFehler(NICHT_GEFUNDEN, "Der zugeordnete Datensatz wurde nicht gefunden.");
        }
    }
    private void versionPruefen(String tabelle, long id, Long version) {
        Long aktuell = jdbc.sql("SELECT version FROM " + tabelle + " WHERE id = :id").param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Datensatz nicht gefunden."));
        if (version == null) {
            throw new FachlicherFehler(UNGUELTIG, "Die Version fehlt. Bitte den Datensatz neu laden.");
        }
        if (!aktuell.equals(version)) {
            throw new FachlicherFehler(KONFLIKT, "Der Datensatz wurde inzwischen geändert. Bitte neu laden.");
        }
    }
    // Tabellen und Spalten stammen ausschließlich aus internen Konstanten, Werte sind gebunden.
    private long speichern(String tabelle, Long id, Long version, Map<String, Object> werte) {
        if (id == null) {
            var platzhalter = new ArrayList<String>();
            for (String spalte : werte.keySet()) {
                platzhalter.add(":" + spalte);
            }
            String sql = "INSERT INTO " + tabelle + " (" + String.join(",", werte.keySet())
                    + ") VALUES (" + String.join(",", platzhalter) + ")";
            var generierterSchluessel = new GeneratedKeyHolder();
            jdbc.sql(sql).params(werte).update(generierterSchluessel, "id");
            return generierterSchluessel.getKey().longValue();
        }

        versionPruefen(tabelle, id, version);
        var zuweisungen = new ArrayList<String>();
        for (String spalte : werte.keySet()) {
            zuweisungen.add(spalte + " = :" + spalte);
        }
        String sql = "UPDATE " + tabelle + " SET " + String.join(",", zuweisungen)
                + ", version = version + 1 WHERE id = :id";
        jdbc.sql(sql).params(werte).param("id", id).update();
        return id;
    }
    private static String nameNormalisieren(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static void datumPruefen(LocalDate datum) {
        ZeitraumRegeln.kalenderjahr(datum);
    }
    private static void zeitraumPruefen(LocalDate von, LocalDate bis) {
        datumPruefen(von);
        if (bis == null) {
            return;
        }
        datumPruefen(bis);
        if (bis.isBefore(von)) {
            throw new FachlicherFehler(UNGUELTIG, "Das Ende darf nicht vor dem Beginn liegen.");
        }
    }
}
