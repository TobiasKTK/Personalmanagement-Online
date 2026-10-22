package de.pemonline.planung;

import de.pemonline.fachlich.*;
import de.pemonline.urlaub.UrlaubsantragRepository;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMIN')")
public class VertretungService {
    public record Eintrag(long id, String genehmiger, String vertreter, LocalDate beginn, LocalDate ende, long version) {}
    public record Eingabe(@NotBlank @Size(max = 80) String genehmiger, @NotBlank @Size(max = 80) String vertreter,
            @NotNull LocalDate beginn, @NotNull LocalDate ende, @PositiveOrZero Long version) {}

    private final JdbcClient jdbc;
    private final UrlaubsantragRepository konten;
    private final VerlaufService verlauf;

    public VertretungService(JdbcClient jdbc, UrlaubsantragRepository konten, VerlaufService verlauf) {
        this.jdbc = jdbc;
        this.konten = konten;
        this.verlauf = verlauf;
    }

    public List<Eintrag> liste() {
        return jdbc.sql("SELECT * FROM genehmiger_vertretung ORDER BY beginn DESC, id DESC").query(Eintrag.class).list();
    }

    @Transactional
    public Eintrag speichern(@Positive Long id, @Valid @NotNull Eingabe eingabe) {
        sperren();
        eingabePruefen(eingabe);
        var vorher = id == null ? null : finden(id);
        versionPruefen(vorher, eingabe.version());
        ueberschneidungPruefen(id, eingabe);

        if (id == null) {
            var generierterSchluessel = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO genehmiger_vertretung (genehmiger, vertreter, beginn, ende) VALUES (:gen, :vert, :beginn, :ende)")
                    .param("gen", eingabe.genehmiger()).param("vert", eingabe.vertreter())
                    .param("beginn", eingabe.beginn()).param("ende", eingabe.ende()).update(generierterSchluessel, "id");
            id = generierterSchluessel.getKey().longValue();
        } else {
            jdbc.sql("UPDATE genehmiger_vertretung SET genehmiger = :gen, vertreter = :vert, beginn = :beginn, ende = :ende, version = version + 1 WHERE id = :id")
                    .param("gen", eingabe.genehmiger()).param("vert", eingabe.vertreter())
                    .param("beginn", eingabe.beginn()).param("ende", eingabe.ende()).param("id", id).update();
        }

        var nachher = finden(id);
        aenderungProtokollieren(vorher, nachher);
        return nachher;
    }

    private void eingabePruefen(Eingabe eingabe) {
        ZeitraumRegeln.kalenderjahr(eingabe.beginn());
        ZeitraumRegeln.kalenderjahr(eingabe.ende());
        if (eingabe.ende().isBefore(eingabe.beginn()) || eingabe.genehmiger().equals(eingabe.vertreter())) {
            throw new FachlicherFehler(UNGUELTIG, "Zeitraum und unterschiedliche Konten prüfen.");
        }
        if (!konten.aktivesKonto(eingabe.genehmiger(), true) || !konten.aktivesKonto(eingabe.vertreter(), true)) {
            throw new FachlicherFehler(UNGUELTIG, "Beide Konten benötigen aktive Personal- oder Administrationsrechte.");
        }
    }

    private void versionPruefen(Eintrag vorher, Long angegebeneVersion) {
        if (vorher == null) {
            if (angegebeneVersion != null) {
                throw konflikt();
            }
            return;
        }
        if (angegebeneVersion == null || angegebeneVersion != vorher.version()) {
            throw konflikt();
        }
    }

    private void ueberschneidungPruefen(Long id, Eingabe eingabe) {
        // Die eigene Vertretung darf bei einer Änderung nicht als Überschneidung zählen.
        long ausgenommeneId = id == null ? -1L : id;
        long ueberschneidungen = jdbc.sql("SELECT COUNT(*) FROM genehmiger_vertretung WHERE genehmiger = :gen AND beginn <= :ende AND ende >= :beginn AND id <> :id")
                .param("gen", eingabe.genehmiger()).param("beginn", eingabe.beginn()).param("ende", eingabe.ende())
                .param("id", ausgenommeneId).query(Long.class).single();
        if (ueberschneidungen > 0) {
            throw new FachlicherFehler(KONFLIKT, "Für diesen Genehmiger besteht bereits eine Vertretung im Zeitraum.");
        }
    }

    @Transactional
    public void loeschen(@Positive long id, @PositiveOrZero long version) {
        sperren();
        var vorher = finden(id);
        if (vorher.version() != version) {
            throw konflikt();
        }
        jdbc.sql("DELETE FROM genehmiger_vertretung WHERE id = :id").param("id", id).update();
        aenderungProtokollieren(vorher, null);
    }

    private void aenderungProtokollieren(Eintrag vorher, Eintrag nachher) {
        String bisherigerGenehmiger = vorher == null ? "" : vorher.genehmiger();
        String neuerGenehmiger = nachher == null ? "" : nachher.genehmiger();
        var mitarbeiterIds = jdbc.sql("SELECT mitarbeiter_id FROM urlaub_zuordnung WHERE genehmiger IN (:alt, :neu)")
                .param("alt", bisherigerGenehmiger).param("neu", neuerGenehmiger).query(Long.class).list();
        for (long mitarbeiterId : mitarbeiterIds) {
            verlauf.speichern(mitarbeiterId, "GENEHMIGERVERTRETUNG", vorher, nachher);
        }
    }

    private Eintrag finden(long id) {
        return jdbc.sql("SELECT * FROM genehmiger_vertretung WHERE id = :id").param("id", id)
                .query(Eintrag.class).optional()
                .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Vertretung nicht gefunden."));
    }

    private void sperren() {
        // Die Überschneidungsprüfung und das Speichern gehören zu einem unteilbaren Vorgang.
        jdbc.sql("SELECT id FROM konten_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
    }

    private FachlicherFehler konflikt() {
        return new FachlicherFehler(KONFLIKT, "Vertretung wurde inzwischen geändert. Bitte neu laden.");
    }
}
