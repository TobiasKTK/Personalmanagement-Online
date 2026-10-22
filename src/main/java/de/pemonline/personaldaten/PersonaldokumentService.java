package de.pemonline.personaldaten;

import de.pemonline.fachlich.FachlicherFehler;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class PersonaldokumentService {
    public enum Art { ABRECHNUNG, LOHNSTEUER, SOZIALVERSICHERUNG, VERTRAG, ZEUGNIS, ZERTIFIKAT, SONSTIGES }
    public enum Quelle { AGENDA, MANUELL }
    public record Metadaten(@NotBlank @Size(max=160) String titel, @NotNull Art art, @NotNull Quelle quelle,
            @NotNull @Pattern(regexp="^$|[0-9]{4}-(0[1-9]|1[0-2])") String monat) {}
    public record Dokument(long id, String titel, Art art, Quelle quelle, String monat, String dateiname, long groesse, Instant erstelltAm,
            boolean selfserviceFreigegeben, long version, long familieId, long dokumentVersion) {}
    public record Freigabe(@NotNull Boolean freigegeben, @NotNull @PositiveOrZero Long version) {}
    public record Seite(List<Dokument> eintraege, long gesamt) {}
    public record BedarfEingabe(@NotNull @PositiveOrZero Long version, @NotNull @Size(max=7) List<@NotNull Art> arten) {}
    public record Bedarf(long version, List<Art> benoetigt, List<Art> vorhanden, List<Art> fehlend) {}
    private final JdbcClient jdbc;
    private final MitarbeiterRepository mitarbeiter;
    private final VerlaufService verlauf;
    private final Clock clock;
    public PersonaldokumentService(JdbcClient jdbc, MitarbeiterRepository mitarbeiter, VerlaufService verlauf, Clock clock) {
        this.jdbc = jdbc; this.mitarbeiter = mitarbeiter; this.verlauf = verlauf; this.clock = clock;
    }
    private static final String SPALTEN = "d.id, d.titel, d.art, d.quelle, d.monat, d.dateiname, d.groesse, d.erstellt_am, d.selfservice_freigegeben, d.version, COALESCE(d.familie_id, d.id) AS familie_id, d.dokument_version";
    private static final String AKTUELL = "NOT EXISTS (SELECT 1 FROM personaldokument n WHERE n.familie_id = COALESCE(d.familie_id, d.id) AND n.dokument_version > d.dokument_version)";
    public Seite liste(@Positive long person, @Min(0) int seite) {
        person(person);
        var eintraege = jdbc.sql("SELECT " + SPALTEN + " FROM personaldokument d WHERE d.mitarbeiter_id = :person AND " + AKTUELL + " ORDER BY d.monat DESC, d.id DESC LIMIT 20 OFFSET :offset")
            .param("person", person).param("offset", (long) seite * 20).query(Dokument.class).list();
        return new Seite(eintraege, jdbc.sql("SELECT COUNT(*) FROM personaldokument d WHERE d.mitarbeiter_id = :person AND " + AKTUELL).param("person", person).query(Long.class).single());
    }
    public List<Dokument> versionen(@Positive long person, @Positive long id) {
        person(person);
        long familie = dokument(person, id).familieId();
        return jdbc.sql("SELECT " + SPALTEN + " FROM personaldokument d WHERE d.mitarbeiter_id = :person AND (d.id = :familie OR d.familie_id = :familie) ORDER BY d.dokument_version DESC")
            .param("person", person).param("familie", familie).query(Dokument.class).list();
    }
    public byte[] inhalt(@Positive long person, @Positive long id) {
        person(person);
        return jdbc.sql("SELECT inhalt FROM personaldokument WHERE mitarbeiter_id = :person AND id = :id")
            .param("person", person).param("id", id).query((rs, n) -> rs.getBytes("inhalt")).optional().orElseThrow(PersonaldokumentService::fehlt);
    }
    @Transactional
    public Dokument importieren(@Positive long person, @NotNull @Valid Metadaten e, @NotNull @Size(min=1, max=5242880) byte[] datei) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        pruefen(e, datei);
        var dokument = einfuegen(person, e, datei, null, 1);
        verlauf.speichern(person, "DOKUMENT_IMPORTIERT", null, dokument);
        return dokument;
    }
    @Transactional
    public Dokument versionHinzufuegen(@Positive long person, @Positive long id, @Positive long dokumentVersion,
            @NotNull @Valid Metadaten e, @NotNull @Size(min=1, max=5242880) byte[] datei) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        var vorher = aktuell(person, dokument(person, id).familieId());
        versionPruefen(vorher, dokumentVersion);
        pruefen(e, datei);
        var dokument = einfuegen(person, e, datei, vorher.familieId(), vorher.dokumentVersion() + 1);
        verlauf.speichern(person, "DOKUMENT_VERSION_HINZUGEFUEGT", vorher, dokument);
        return dokument;
    }
    private void pruefen(Metadaten e, byte[] datei) {
        String anfang = new String(datei, 0, Math.min(8, datei.length), StandardCharsets.US_ASCII);
        String ende = new String(datei, Math.max(0, datei.length - 1024), Math.min(1024, datei.length), StandardCharsets.US_ASCII);
        if (!anfang.matches("%PDF-[12]\\.[0-9].*") || !ende.contains("%%EOF")) throw new FachlicherFehler(UNGUELTIG, "Bitte eine PDF-Datei mit höchstens 5 MiB auswählen.");
        if (e.art() == Art.ABRECHNUNG && e.monat().isBlank()) throw new FachlicherFehler(UNGUELTIG, "Bitte den Abrechnungsmonat angeben.");
        if (!e.monat().isBlank() && YearMonth.parse(e.monat()).getYear() < 1) throw new FachlicherFehler(UNGUELTIG, "Ungültiger Abrechnungsmonat.");
    }
    private Dokument einfuegen(long person, Metadaten e, byte[] datei, Long familie, long dokumentVersion) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO personaldokument (mitarbeiter_id, titel, art, quelle, monat, dateiname, groesse, inhalt, erstellt_am, familie_id, dokument_version) VALUES (:person, :titel, :art, :quelle, :monat, :name, :groesse, :inhalt, :zeit, :familie, :dokumentVersion)")
            .param("person", person).param("titel", e.titel().strip()).param("art", e.art().name()).param("quelle", e.quelle().name()).param("monat", e.monat())
            .param("name", "dokument.pdf").param("groesse", datei.length).param("inhalt", datei).param("zeit", clock.instant())
            .param("familie", familie, java.sql.Types.BIGINT).param("dokumentVersion", dokumentVersion).update(key, "id");
        return dokument(person, key.getKey().longValue());
    }
    @Transactional
    public Dokument freigeben(@Positive long person, @Positive long id, @NotNull @Valid Freigabe e) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        var vorher = dokument(person, id);
        if (vorher.art() != Art.ABRECHNUNG) throw new FachlicherFehler(UNGUELTIG, "Nur Gehaltsabrechnungen können im Self-Service freigegeben werden.");
        if (vorher.version() != e.version()) throw new FachlicherFehler(KONFLIKT, "Die Dokumentfreigabe wurde inzwischen geändert. Bitte neu laden.");
        jdbc.sql("UPDATE personaldokument SET selfservice_freigegeben = :frei, version = version + 1 WHERE id = :id AND mitarbeiter_id = :person")
            .param("frei", e.freigegeben()).param("id", id).param("person", person).update();
        var nachher = dokument(person, id);
        verlauf.speichern(person, "DOKUMENT_FREIGABE", vorher, nachher);
        return nachher;
    }
    @Transactional
    public void loeschen(@Positive long person, @Positive long id, @Positive Long dokumentVersion) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        var vorher = aktuell(person, dokument(person, id).familieId());
        // Ältere Clients kennen nur die erste PDF: ohne Stand dürfen sie keine späteren Fassungen löschen.
        versionPruefen(vorher, dokumentVersion == null ? 1 : dokumentVersion);
        jdbc.sql("DELETE FROM personaldokument WHERE id = :id AND mitarbeiter_id = :person").param("id", vorher.familieId()).param("person", person).update();
        verlauf.speichern(person, "DOKUMENT_GELOESCHT", vorher, null);
    }
    public Bedarf bedarf(@Positive long person) { person(person); return bedarfLaden(person); }
    @Transactional
    public Bedarf bedarfSpeichern(@Positive long person, @NotNull @Valid BedarfEingabe e) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        if (new HashSet<>(e.arten()).size() != e.arten().size()) throw new FachlicherFehler(UNGUELTIG, "Bitte jede Dokumentart nur einmal auswählen.");
        var vorher = bedarfLaden(person);
        if (vorher.version() != e.version()) throw new FachlicherFehler(KONFLIKT, "Der Dokumentbedarf wurde inzwischen geändert. Bitte den Dokumentbedarf neu laden.");
        if (vorher.version() == 0) {
            jdbc.sql("INSERT INTO personaldokument_bedarf (mitarbeiter_id, version) VALUES (:person, 1)").param("person", person).update();
        } else {
            jdbc.sql("UPDATE personaldokument_bedarf SET version = version + 1 WHERE mitarbeiter_id = :person").param("person", person).update();
        }
        jdbc.sql("DELETE FROM personaldokument_bedarf_art WHERE mitarbeiter_id = :person").param("person", person).update();
        for (Art art : e.arten()) jdbc.sql("INSERT INTO personaldokument_bedarf_art (mitarbeiter_id, art) VALUES (:person, :art)").param("person", person).param("art", art.name()).update();
        var nachher = bedarfLaden(person);
        verlauf.speichern(person, "DOKUMENTBEDARF_GEAENDERT", vorher, nachher);
        return nachher;
    }
    private Bedarf bedarfLaden(long person) {
        long version = jdbc.sql("SELECT version FROM personaldokument_bedarf WHERE mitarbeiter_id = :person").param("person", person).query(Long.class).optional().orElse(0L);
        var benoetigt = arten("SELECT art FROM personaldokument_bedarf_art WHERE mitarbeiter_id = :person", person);
        var vorhanden = arten("SELECT DISTINCT d.art FROM personaldokument d WHERE d.mitarbeiter_id = :person AND " + AKTUELL, person);
        return new Bedarf(version, benoetigt, vorhanden, benoetigt.stream().filter(art -> !vorhanden.contains(art)).toList());
    }
    private List<Art> arten(String sql, long person) {
        var arten = EnumSet.noneOf(Art.class);
        jdbc.sql(sql).param("person", person).query(String.class).list().forEach(wert -> arten.add(Art.valueOf(wert)));
        return List.copyOf(arten);
    }
    private Dokument dokument(long person, long id) {
        return jdbc.sql("SELECT " + SPALTEN + " FROM personaldokument d WHERE d.id = :id AND d.mitarbeiter_id = :person")
            .param("id", id).param("person", person).query(Dokument.class).optional().orElseThrow(PersonaldokumentService::fehlt);
    }
    private Dokument aktuell(long person, long familie) {
        return jdbc.sql("SELECT " + SPALTEN + " FROM personaldokument d WHERE d.mitarbeiter_id = :person AND (d.id = :familie OR d.familie_id = :familie) ORDER BY d.dokument_version DESC LIMIT 1")
            .param("person", person).param("familie", familie).query(Dokument.class).optional().orElseThrow(PersonaldokumentService::fehlt);
    }
    private void versionPruefen(Dokument aktuell, long erwartet) {
        if (aktuell.dokumentVersion() != erwartet) throw new FachlicherFehler(KONFLIKT, "Es liegt bereits eine neuere Version dieses Dokuments vor. Bitte die Dokumente aktualisieren und die neue Version prüfen.");
    }
    private void person(long id) { if (mitarbeiter.finden(id, LocalDate.now(clock)).isEmpty()) throw fehlt(); }
    private static FachlicherFehler fehlt() { return new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter oder Dokument nicht gefunden."); }
}
