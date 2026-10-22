package de.pemonline.personalprozesse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class PersonalprozessModel {
    private PersonalprozessModel() {}
    public record Vorlagenaufgabe(@NotBlank @Size(max = 160) String titel, @NotNull @Min(-365) @Max(365) Integer tage) {}
    public record Vorlage(long id, String typ, String titel, long version, List<Vorlagenaufgabe> aufgaben) {}
    public record VorlagenEingabe(@NotBlank @Pattern(regexp = "ONBOARDING|OFFBOARDING") String typ,
            @NotBlank @Size(max = 160) String titel,
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Vorlagenaufgabe> aufgaben,
            @PositiveOrZero Long version) {}
    public record Start(@Positive long vorlageId, @NotNull @PositiveOrZero Long vorlageVersion,
            @Positive long mitarbeiterId, @NotNull LocalDate bezugsdatum,
            @NotBlank @Size(max = 100) String verantwortlich) {}
    public record Aufgabe(long id, long vorgangId, long mitarbeiterId, String mitarbeiterName,
            String typ, String vorgangTitel, String titel, String verantwortlich,
            LocalDate faelligAm, String status, long version) {}
    public record Vorgang(long id, List<Aufgabe> aufgaben) {}
    public record Seite(List<Aufgabe> eintraege, long gesamt) {}
    public record Status(@NotBlank @Pattern(regexp = "OFFEN|IN_BEARBEITUNG|ERLEDIGT") String status,
            @NotNull @PositiveOrZero Long version) {}
    public record Zuordnung(@NotBlank @Size(max = 100) String verantwortlich, @NotNull LocalDate faelligAm,
            @NotNull @PositiveOrZero Long version) {}
}
