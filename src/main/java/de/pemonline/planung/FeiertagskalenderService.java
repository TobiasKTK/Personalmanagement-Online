package de.pemonline.planung;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.urlaub.UrlaubskontoService;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
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
public class FeiertagskalenderService {
    public record Kalender(long id, String name, int jahr, Set<LocalDate> feiertage, long version) {}
    public record Eingabe(@NotBlank @Size(max = 120) String name, @Min(1) @Max(9999) int jahr,
                         @NotNull @Size(max = 60) Set<@NotNull LocalDate> feiertage, @PositiveOrZero Long version) {}
    private final JdbcClient jdbc;
    private final PlanungSperre sperre;
    private final MitarbeiterRepository mitarbeiter;
    private final UrlaubskontoService urlaub;
    private final VerlaufService verlauf;
    public FeiertagskalenderService(JdbcClient jdbc, PlanungSperre sperre, MitarbeiterRepository mitarbeiter, UrlaubskontoService urlaub, VerlaufService verlauf) {
        this.jdbc = jdbc;
        this.sperre = sperre;
        this.mitarbeiter = mitarbeiter;
        this.urlaub = urlaub;
        this.verlauf = verlauf;
    }
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public List<Kalender> liste(@Min(1) @Max(9999) Integer jahr) {
        return jdbc.sql("SELECT * FROM feiertagskalender WHERE (:jahr IS NULL OR jahr = :jahr) ORDER BY jahr DESC, name, id")
            .param("jahr", jahr, java.sql.Types.INTEGER).query((rs, n) -> new Kalender(rs.getLong("id"), rs.getString("name"), rs.getInt("jahr"),
                feiertageLesen(rs.getString("feiertage")), rs.getLong("version"))).list();
    }

    private Set<LocalDate> feiertageLesen(String gespeicherteTage) {
        var feiertage = new TreeSet<LocalDate>();
        for (String tag : gespeicherteTage.split(",")) {
            if (!tag.isBlank()) {
                feiertage.add(LocalDate.parse(tag));
            }
        }
        return feiertage;
    }
    @Transactional
    public Kalender speichern(@Positive Long id, @NotNull @Valid Eingabe eingabe) {
        sperre.sperren();
        for (LocalDate feiertag : eingabe.feiertage()) {
            if (feiertag.getYear() != eingabe.jahr()) {
                throw new FachlicherFehler(UNGUELTIG, "Alle Feiertage müssen im Kalenderjahr liegen.");
            }
        }
        var vorher = id == null ? null : finden(id);
        if (id == null) {
            if (eingabe.version() != null) {
                throw konflikt();
            }
        } else {
            if (vorher == null || eingabe.version() == null || eingabe.version() != vorher.version()) {
                throw konflikt();
            }
        }
        var mitarbeiterIds = id == null ? List.<Long>of() : zugeordneteMitarbeiterIds(id);
        if (id != null && !mitarbeiterIds.isEmpty() && vorher != null && vorher.jahr() != eingabe.jahr()) {
            throw new FachlicherFehler(KONFLIKT, "Das Jahr eines zugeordneten Kalenders kann nicht geändert werden.");
        }
        for (long mitarbeiterId : mitarbeiterIds) {
            mitarbeiter.sperren(mitarbeiterId);
        }

        long gespeicherteId = kalenderSpeichern(id, eingabe);
        var nachher = finden(gespeicherteId);
        betroffeneKontenPruefenUndProtokollieren(mitarbeiterIds, vorher, nachher);
        return nachher;
    }

    private long kalenderSpeichern(Long id, Eingabe eingabe) {
        String name = eingabe.name().strip();
        String normalisierterName = name.toLowerCase(Locale.ROOT);
        if (jdbc.sql("SELECT COUNT(*) FROM feiertagskalender WHERE name_normalisiert = :name AND jahr = :jahr AND id <> :id")
            .param("name", normalisierterName).param("jahr", eingabe.jahr()).param("id", id == null ? -1L : id).query(Long.class).single() > 0) {
            throw new FachlicherFehler(KONFLIKT, "Für diesen Standort und dieses Jahr besteht bereits ein Kalender.");
        }
        String tage = eingabe.feiertage().stream().sorted().map(d -> Objects.requireNonNull(d).toString()).collect(Collectors.joining(","));
        if (id == null) {
            var generierterSchluessel = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO feiertagskalender (name, name_normalisiert, jahr, feiertage) VALUES (:name, :normal, :jahr, :tage)")
                .param("name", name).param("normal", normalisierterName).param("jahr", eingabe.jahr()).param("tage", tage).update(generierterSchluessel, "id");
            id = generierterSchluessel.getKey().longValue();
        } else {
            jdbc.sql("UPDATE feiertagskalender SET name = :name, name_normalisiert = :normal, jahr = :jahr, feiertage = :tage, version = version + 1 WHERE id = :id")
                .param("name", name).param("normal", normalisierterName).param("jahr", eingabe.jahr()).param("tage", tage).param("id", id).update();
            // Ein älteres Kontenformular darf die geänderten Feiertage nicht unbemerkt überschreiben.
            jdbc.sql("UPDATE urlaubskonto SET version = version + 1 WHERE feiertagskalender_id = :id").param("id", id).update();
        }
        return id;
    }

    private void betroffeneKontenPruefenUndProtokollieren(List<Long> mitarbeiterIds, Kalender vorher, Kalender nachher) {
        // Weniger Feiertage können mehr Urlaub verbrauchen. Bei Unterdeckung wird die ganze Änderung zurückgerollt.
        for (long mitarbeiterId : mitarbeiterIds) {
            urlaub.pruefen(mitarbeiterId);
            verlauf.speichern(mitarbeiterId, "FEIERTAGSKALENDER_GEAENDERT", vorher, nachher);
        }
    }
    @Transactional
    public void loeschen(@Positive long id, @PositiveOrZero long version) {
        sperre.sperren();
        var kalender = finden(id);
        if (kalender.version() != version) {
            throw konflikt();
        }
        if (!zugeordneteMitarbeiterIds(id).isEmpty()) {
            throw new FachlicherFehler(KONFLIKT, "Dieser Kalender ist noch Urlaubskonten zugeordnet.");
        }
        jdbc.sql("DELETE FROM feiertagskalender WHERE id = :id").param("id", id).update();
    }
    private Kalender finden(long id) {
        return liste(null).stream().filter(kalender -> kalender.id() == id).findFirst()
                .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Kalender nicht gefunden."));
    }

    private List<Long> zugeordneteMitarbeiterIds(long kalenderId) {
        return jdbc.sql("SELECT mitarbeiter_id FROM urlaubskonto WHERE feiertagskalender_id = :id ORDER BY mitarbeiter_id")
                .param("id", kalenderId).query(Long.class).list();
    }

    private FachlicherFehler konflikt() {
        return new FachlicherFehler(KONFLIKT, "Kalender wurde inzwischen geändert. Bitte neu laden.");
    }
}
