package de.pemonline.personaldaten;

import de.pemonline.fachlich.FachlicherFehler;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
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
    public enum Art { ABRECHNUNG, LOHNSTEUER, SOZIALVERSICHERUNG, VERTRAG, SONSTIGES }
    public enum Quelle { AGENDA, MANUELL }
    public record Metadaten(@NotBlank @Size(max=160) String titel, @NotNull Art art, @NotNull Quelle quelle,
            @NotNull @Pattern(regexp="^$|[0-9]{4}-(0[1-9]|1[0-2])") String monat) {}
    public record Dokument(long id, String titel, Art art, Quelle quelle, String monat, String dateiname, long groesse, Instant erstelltAm) {}
    public record Seite(List<Dokument> eintraege, long gesamt) {}
    private final JdbcClient jdbc;
    private final MitarbeiterRepository mitarbeiter;
    private final VerlaufService verlauf;
    private final Clock clock;
    public PersonaldokumentService(JdbcClient jdbc, MitarbeiterRepository mitarbeiter, VerlaufService verlauf, Clock clock) {
        this.jdbc = jdbc; this.mitarbeiter = mitarbeiter; this.verlauf = verlauf; this.clock = clock;
    }
    private static final String SPALTEN = "id, titel, art, quelle, monat, dateiname, groesse, erstellt_am";
    public Seite liste(@Positive long person, @Min(0) int seite) {
        person(person);
        var eintraege = jdbc.sql("SELECT " + SPALTEN + " FROM personaldokument WHERE mitarbeiter_id = :person ORDER BY monat DESC, id DESC LIMIT 20 OFFSET :offset")
            .param("person", person).param("offset", (long) seite * 20).query(Dokument.class).list();
        return new Seite(eintraege, jdbc.sql("SELECT COUNT(*) FROM personaldokument WHERE mitarbeiter_id = :person").param("person", person).query(Long.class).single());
    }
    public byte[] inhalt(@Positive long person, @Positive long id) {
        person(person);
        return jdbc.sql("SELECT inhalt FROM personaldokument WHERE mitarbeiter_id = :person AND id = :id")
            .param("person", person).param("id", id).query((rs, n) -> rs.getBytes("inhalt")).optional().orElseThrow(PersonaldokumentService::fehlt);
    }
    @Transactional
    public Dokument importieren(@Positive long person, @NotNull @Valid Metadaten e, @NotNull @Size(min=1, max=5242880) byte[] datei) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        String anfang = new String(datei, 0, Math.min(8, datei.length), StandardCharsets.US_ASCII);
        String ende = new String(datei, Math.max(0, datei.length - 1024), Math.min(1024, datei.length), StandardCharsets.US_ASCII);
        if (!anfang.matches("%PDF-[12]\\.[0-9].*") || !ende.contains("%%EOF")) throw new FachlicherFehler(UNGUELTIG, "Bitte eine PDF-Datei mit höchstens 5 MiB auswählen.");
        if (e.art() == Art.ABRECHNUNG && e.monat().isBlank()) throw new FachlicherFehler(UNGUELTIG, "Bitte den Abrechnungsmonat angeben.");
        if (!e.monat().isBlank() && YearMonth.parse(e.monat()).getYear() < 1) throw new FachlicherFehler(UNGUELTIG, "Ungültiger Abrechnungsmonat.");
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO personaldokument (mitarbeiter_id, titel, art, quelle, monat, dateiname, groesse, inhalt, erstellt_am) VALUES (:person, :titel, :art, :quelle, :monat, :name, :groesse, :inhalt, :zeit)")
            .param("person", person).param("titel", e.titel().strip()).param("art", e.art().name()).param("quelle", e.quelle().name()).param("monat", e.monat())
            .param("name", "dokument.pdf").param("groesse", datei.length).param("inhalt", datei).param("zeit", clock.instant()).update(key, "id");
        var dokument = jdbc.sql("SELECT " + SPALTEN + " FROM personaldokument WHERE id = :id").param("id", key.getKey().longValue()).query(Dokument.class).single();
        verlauf.speichern(person, "DOKUMENT_IMPORTIERT", null, dokument);
        return dokument;
    }
    @Transactional
    public void loeschen(@Positive long person, @Positive long id) {
        if (!mitarbeiter.sperren(person)) throw fehlt();
        var vorher = jdbc.sql("SELECT " + SPALTEN + " FROM personaldokument WHERE id = :id AND mitarbeiter_id = :person").param("id", id).param("person", person).query(Dokument.class).optional().orElseThrow(PersonaldokumentService::fehlt);
        jdbc.sql("DELETE FROM personaldokument WHERE id = :id AND mitarbeiter_id = :person").param("id", id).param("person", person).update();
        verlauf.speichern(person, "DOKUMENT_GELOESCHT", vorher, null);
    }
    private void person(long id) { if (mitarbeiter.finden(id, LocalDate.now(clock)).isEmpty()) throw fehlt(); }
    private static FachlicherFehler fehlt() { return new FachlicherFehler(NICHT_GEFUNDEN, "Mitarbeiter oder Dokument nicht gefunden."); }
}
