package de.pemonline.mitarbeiter;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import de.pemonline.personaldaten.PersonaldatenModel.Stellenzuordnung;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record MitarbeiterEingabe(
        @Positive(message = "Die Personalnummer muss positiv sein.")
        @Max(value = 2147483647, message = "Die Personalnummer darf höchstens 2147483647 sein.")
        Long personalnummer,
        @Size(max = 80, message = "Der Titel darf höchstens 80 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String titel,
        @NotBlank(message = "Bitte einen Nachnamen eingeben.")
        @Size(max = 120, message = "Der Nachname darf höchstens 120 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String nachname,
        @NotBlank(message = "Bitte einen Vornamen eingeben.")
        @Size(max = 120, message = "Der Vorname darf höchstens 120 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String vorname,
        @Size(max = 120, message = "Der Geburtsname darf höchstens 120 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String geburtsname,
        @Size(max = 20, message = "Das Kürzel darf höchstens 20 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String kuerzel,
        @PastOrPresent(message = "Das Geburtsdatum darf nicht in der Zukunft liegen.")
        LocalDate geburtsdatum,
        @NotNull(message = "Bitte ein Eintrittsdatum eingeben.")
        LocalDate eintrittsdatum,
        LocalDate austrittsdatum,
        @Size(max = 120, message = "Die Organisationseinheit darf höchstens 120 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String organisationseinheit,
        @Size(max = 120, message = "Die Stelle darf höchstens 120 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String stelle,
        @Size(max = 80, message = "Die Beschäftigungsart darf höchstens 80 Zeichen enthalten.")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Keine Steuerzeichen verwenden.")
        String beschaeftigungsart,
        @NotNull(message = "Bitte Wochenstunden eingeben.")
        @DecimalMin(value = "0.5", message = "Mindestens 0,5 Wochenstunden eingeben.")
        @DecimalMax(value = "168", message = "Höchstens 168 Wochenstunden eingeben.")
        @Digits(integer = 3, fraction = 2, message = "Höchstens zwei Nachkommastellen verwenden.")
        BigDecimal wochenstunden,
        @PositiveOrZero(message = "Die Version darf nicht negativ sein.")
        Long version,
        @Positive Long organisationseinheitId,
        LocalDate datenGueltigAb,
        @Valid Stellenzuordnung stellenzuordnung) {

    public MitarbeiterEingabe(Long personalnummer, String titel, String nachname, String vorname, String geburtsname,
            String kuerzel, LocalDate geburtsdatum, LocalDate eintrittsdatum, LocalDate austrittsdatum,
            String organisationseinheit, String stelle, String beschaeftigungsart,
            BigDecimal wochenstunden, Long version, Long organisationseinheitId, LocalDate datenGueltigAb) {
        this(personalnummer, titel, nachname, vorname, geburtsname, kuerzel, geburtsdatum, eintrittsdatum, austrittsdatum,
                organisationseinheit, stelle, beschaeftigungsart, wochenstunden, version, organisationseinheitId, datenGueltigAb, null);
    }

    public MitarbeiterEingabe(Long personalnummer, String titel, String nachname, String vorname, String geburtsname,
            String kuerzel, LocalDate geburtsdatum, LocalDate eintrittsdatum, LocalDate austrittsdatum,
            String organisationseinheit, String stelle, String beschaeftigungsart,
            BigDecimal wochenstunden, Long version, Long organisationseinheitId) {
        this(personalnummer, titel, nachname, vorname, geburtsname, kuerzel, geburtsdatum, eintrittsdatum, austrittsdatum,
                organisationseinheit, stelle, beschaeftigungsart, wochenstunden, version, organisationseinheitId, null);
    }

    public MitarbeiterEingabe(Long personalnummer, String titel, String nachname, String vorname, String geburtsname,
            String kuerzel, LocalDate geburtsdatum, LocalDate eintrittsdatum, LocalDate austrittsdatum,
            String organisationseinheit, String stelle, String beschaeftigungsart,
            BigDecimal wochenstunden, Long version) {
        this(personalnummer, titel, nachname, vorname, geburtsname, kuerzel, geburtsdatum, eintrittsdatum, austrittsdatum,
                organisationseinheit, stelle, beschaeftigungsart, wochenstunden, version, null);
    }

    public MitarbeiterEingabe {
        titel = bereinigen(titel);
        nachname = bereinigen(nachname);
        vorname = bereinigen(vorname);
        geburtsname = bereinigen(geburtsname);
        kuerzel = bereinigen(kuerzel);
        organisationseinheit = bereinigen(organisationseinheit);
        stelle = bereinigen(stelle);
        beschaeftigungsart = bereinigen(beschaeftigungsart);
    }

    public String name() {
        return (vorname + " " + nachname).strip();
    }

    @JsonAnySetter
    public void unbekannt(String name, Object wert) {
        throw new IllegalArgumentException("Unbekanntes oder schreibgeschütztes Mitarbeiterfeld.");
    }

    private static String bereinigen(String text) {
        return text == null ? "" : text.strip();
    }
}
