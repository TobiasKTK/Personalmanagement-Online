package de.pemonline.fortbildung;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class FortbildungsModel {
    private FortbildungsModel() {}
    public record Schulung(long id, String titel, String anbieter, LocalDate beginn, LocalDate ende,
            long kenntnisId, int niveau, long version, List<Teilnahme> teilnahmen) {}
    public record Teilnahme(long mitarbeiterId, String name, long personalnummer, boolean abgeschlossen,
            Long nachweisId, Integer niveau, LocalDate erworbenAm, LocalDate gueltigBis, String nachweis) {}
    public record SchulungsEingabe(@NotBlank @Size(max=160) String titel, @NotNull @Size(max=240) String anbieter,
            @NotNull LocalDate beginn, @NotNull LocalDate ende, @Positive long kenntnisId,
            @Min(1) @Max(5) int niveau, @PositiveOrZero Long version) {
        public SchulungsEingabe { titel = titel == null ? null : titel.strip(); anbieter = anbieter == null ? null : anbieter.strip(); }
    }
    public record Zuordnung(@NotNull @Size(min=1, max=500) List<@NotNull @Positive Long> mitarbeiterIds,
            @NotNull @PositiveOrZero Long version) {}
    public record Abschluss(@NotNull @PositiveOrZero Long version, @NotNull LocalDate erworbenAm,
            LocalDate gueltigBis, @NotBlank @Size(max=1000) String nachweis,
            @Positive Long vorhandenerNachweisId, @PositiveOrZero Long nachweisVersion) {
        public Abschluss { nachweis = nachweis == null ? null : nachweis.strip(); }
    }
}
