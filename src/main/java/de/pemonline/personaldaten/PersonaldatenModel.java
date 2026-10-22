package de.pemonline.personaldaten;

import de.pemonline.mitarbeiter.Mitarbeiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class PersonaldatenModel {
    private PersonaldatenModel() {}
    public enum Basis { MONAT, STUNDE, JAHR, EINMALIG }
    public record Bank(@Size(max=120) String kontoinhaber, @Size(max=34) String iban,
            @Pattern(regexp="^$|[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?") String bic, @Size(max=120) String institut) {
        public Bank { kontoinhaber = text(kontoinhaber); iban = text(iban).replaceAll("\\s", "").toUpperCase(Locale.ROOT); bic = text(bic).toUpperCase(Locale.ROOT); institut = text(institut); }
    }
    public record Kostenstelle(@NotBlank @Size(max=40) String nummer, @Size(max=120) String bezeichnung,
            @NotNull @DecimalMin("0.01") @DecimalMax("100") @Digits(integer=3, fraction=2) BigDecimal anteil) {
        public Kostenstelle { nummer = text(nummer); bezeichnung = text(bezeichnung); }
    }
    public record Vertretung(@Positive long personId, @NotBlank @Size(max=240) String aufgabe, @NotNull LocalDate beginn, LocalDate ende) {
        public Vertretung { aufgabe = text(aufgabe); }
    }
    public record Schwerbehinderung(@Min(0) @Max(100) Integer grad, boolean gleichgestellt,
            @Size(max=80) String merkzeichen, @Size(max=80) String ausweisnummer, LocalDate gueltigAb, LocalDate gueltigBis) {
        public Schwerbehinderung { merkzeichen = text(merkzeichen); ausweisnummer = text(ausweisnummer); }
    }
    public record Verguetung(@NotNull LocalDate gueltigAb, @NotBlank @Size(max=40) String lohnart,
            @NotBlank @Size(max=120) String bezeichnung, @NotNull @DecimalMin("0") @Digits(integer=9, fraction=2) BigDecimal betrag,
            @NotBlank @Pattern(regexp="[A-Z]{3}") String waehrung, @NotNull Basis basis) {
        public Verguetung { lohnart = text(lohnart); bezeichnung = text(bezeichnung); waehrung = text(waehrung).toUpperCase(Locale.ROOT); }
    }
    public record Daten(@NotNull @Valid Bank bank, @Positive Long profilId, boolean disziplinarisch, boolean fachlich,
            @NotNull @Size(max=50) List<@NotNull @Valid Kostenstelle> kostenstellen,
            @NotNull @Size(max=100) List<@NotNull @Valid Vertretung> vertretungen,
            @NotNull @Valid Schwerbehinderung schwerbehinderung, @Size(max=240) String austrittsgrund, LocalDate kuendigungsdatum,
            @NotNull @Size(max=500) List<@NotNull @Valid Verguetung> verguetungen, @NotNull @PositiveOrZero Long version) {
        public Daten { austrittsgrund = text(austrittsgrund); }
        public Daten mitVersion(long v) { return new Daten(bank, profilId, disziplinarisch, fachlich, kostenstellen, vertretungen, schwerbehinderung, austrittsgrund, kuendigungsdatum, verguetungen, v); }
        public Daten ohneAustritt() { return new Daten(bank, profilId, disziplinarisch, fachlich, kostenstellen, vertretungen, schwerbehinderung, "", null, verguetungen, version); }
        public static Daten leer() { return new Daten(new Bank("", "", "", ""), null, false, false, List.of(), List.of(), new Schwerbehinderung(null, false, "", "", null, null), "", null, List.of(), 0L); }
    }
    public record Zeit(long id, LocalDate beginn, LocalDate ende, String austrittsgrund, LocalDate kuendigungsdatum) {}
    public record ZeitEingabe(@NotNull LocalDate beginn, @NotNull LocalDate ende, @Size(max=240) String austrittsgrund,
            LocalDate kuendigungsdatum, @NotNull @PositiveOrZero Long version) { public ZeitEingabe { austrittsgrund = text(austrittsgrund); } }
    public record Wiedereintritt(@NotNull LocalDate eintritt, @NotNull @PositiveOrZero Long version, @NotNull @PositiveOrZero Long mitarbeiterVersion) {}
    public record Akte(Mitarbeiter person, Daten daten, List<Zeit> fruehereZeiten, long betriebszugehoerigkeitTage, LocalDate stichtag, String profilName) {}
    public record Revision(long id, Instant zeitpunkt, String bearbeiter, Daten daten, Mitarbeiter person, List<Zeit> fruehereZeiten, String profilName) {}
    public record Historie(List<Revision> eintraege, long gesamt) {}
    static String text(String s) { return s == null ? "" : s.strip(); }
}
