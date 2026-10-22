package de.pemonline.urlaub;

import java.time.LocalDate;
import java.util.Set;
import jakarta.validation.constraints.*;

public record Urlaubskonto(long mitarbeiterId, int jahr, boolean eingerichtet, double anspruch, double uebertrag,
        Set<Integer> arbeitstage, Set<LocalDate> feiertage, double genommen, double geplant, double beantragt,
        double rest, double verfuegbar, Long version, Long feiertagskalenderId, String kalenderName,
        Set<LocalDate> kalenderFeiertage, Set<LocalDate> ausnahmen) {
    public record Eingabe(@DecimalMin("0") @DecimalMax("366") @Digits(integer = 3, fraction = 1) double anspruch,
            @DecimalMin("0") @DecimalMax("366") @Digits(integer = 3, fraction = 1) double uebertrag,
            @NotNull @Size(min = 1, max = 7) Set<@NotNull @Min(1) @Max(7) Integer> arbeitstage,
            @NotNull @Size(max = 60) Set<@NotNull LocalDate> feiertage,
            @PositiveOrZero Long version, @Positive Long feiertagskalenderId, @Size(max = 60) Set<@NotNull LocalDate> ausnahmen) {
        public Eingabe { ausnahmen = ausnahmen == null ? Set.of() : java.util.Collections.unmodifiableSet(new java.util.HashSet<>(ausnahmen)); }
        public Eingabe(double anspruch, double uebertrag, Set<Integer> arbeitstage, Set<LocalDate> feiertage, Long version) { this(anspruch, uebertrag, arbeitstage, feiertage, version, null, Set.of()); }
        @AssertTrue(message = "Anspruch und Übertrag müssen ganze oder halbe Tage sein.")
        public boolean isHalbeTage() { return Double.isFinite(anspruch) && Double.isFinite(uebertrag) && anspruch * 2 == Math.rint(anspruch * 2) && uebertrag * 2 == Math.rint(uebertrag * 2); }
    }
}
