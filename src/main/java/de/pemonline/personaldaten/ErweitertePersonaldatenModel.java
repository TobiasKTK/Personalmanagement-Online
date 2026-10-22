package de.pemonline.personaldaten;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ErweitertePersonaldatenModel {
    private ErweitertePersonaldatenModel() {}
    public enum Kontaktart { INTERN, GESCHAEFTLICH, NOTFALL }
    public enum Bildungsart { SCHULE, BERUF, HOCHSCHULE, SONSTIGES }
    public enum Status { GEPLANT, LAUFEND, ABGESCHLOSSEN, ABGEBROCHEN }
    public record Kontakt(@NotNull Kontaktart art, @NotBlank @Size(max=160) String name,
            @Size(max=160) String beziehung, @Size(max=80) String telefon, @Email @Size(max=254) String email,
            @NotNull @Min(1) @Max(99) Integer prioritaet, @Size(max=1000) String hinweis) {
        public Kontakt { name = text(name); beziehung = text(beziehung); telefon = text(telefon); email = text(email); hinweis = text(hinweis); }
    }
    public record Vollmacht(UUID id, @NotBlank @Size(max=160) String bezeichnung, @Size(max=1000) String umfang,
            @Size(max=160) String erteiltDurch, LocalDate beginn, LocalDate ende, @Size(max=1000) String hinweis) {
        public Vollmacht { if (id == null) id = UUID.randomUUID(); bezeichnung = text(bezeichnung); umfang = text(umfang); erteiltDurch = text(erteiltDurch); hinweis = text(hinweis); }
    }
    public record Kostentraeger(@NotBlank @Size(max=40) String nummer, @NotBlank @Size(max=160) String bezeichnung,
            LocalDate beginn, LocalDate ende, @Size(max=1000) String hinweis) {
        public Kostentraeger { nummer = text(nummer); bezeichnung = text(bezeichnung); hinweis = text(hinweis); }
    }
    public record Beauftragung(UUID id, @NotBlank @Size(max=160) String aufgabe, @Size(max=160) String bereich,
            @Size(max=160) String bestelltDurch, LocalDate beginn, LocalDate ende, @Size(max=1000) String hinweis) {
        public Beauftragung { if (id == null) id = UUID.randomUUID(); aufgabe = text(aufgabe); bereich = text(bereich); bestelltDurch = text(bestelltDurch); hinweis = text(hinweis); }
    }
    public record Bildung(@NotNull Bildungsart art, @NotBlank @Size(max=160) String einrichtung,
            @Size(max=160) String fachrichtung, @Size(max=160) String abschluss, LocalDate beginn, LocalDate ende,
            @NotNull Status status, @Size(max=1000) String hinweis) {
        public Bildung { einrichtung = text(einrichtung); fachrichtung = text(fachrichtung); abschluss = text(abschluss); hinweis = text(hinweis); }
    }
    public record Fortbildung(UUID id, @NotBlank @Size(max=160) String titel, @Size(max=160) String anbieter,
            LocalDate beginn, LocalDate ende, @DecimalMin("0") @Digits(integer=5, fraction=2) BigDecimal dauerStunden,
            @NotNull Status status, @Size(max=240) String nachweis, LocalDate gueltigBis, @Size(max=1000) String hinweis) {
        public Fortbildung { if (id == null) id = UUID.randomUUID(); titel = text(titel); anbieter = text(anbieter); nachweis = text(nachweis); hinweis = text(hinweis); }
    }
    public record Fahrzeug(@NotBlank @Size(max=160) String hersteller, @NotBlank @Size(max=160) String modell,
            @NotBlank @Size(max=80) String kennung, LocalDate beginn, LocalDate ende, @Size(max=160) String leasinganbieter,
            @DecimalMin("0") @Digits(integer=7, fraction=2) BigDecimal monatsrate, @Size(max=1000) String hinweis) {
        public Fahrzeug { hersteller = text(hersteller); modell = text(modell); kennung = text(kennung); leasinganbieter = text(leasinganbieter); hinweis = text(hinweis); }
    }
    public record Daten(
            @NotNull @Size(max=100) List<@NotNull @Valid Kontakt> kontakte,
            @NotNull @Size(max=100) List<@NotNull @Valid Vollmacht> vollmachten,
            @NotNull @Size(max=100) List<@NotNull @Valid Kostentraeger> kostentraeger,
            @NotNull @Size(max=100) List<@NotNull @Valid Beauftragung> beauftragungen,
            @NotNull @Size(max=100) List<@NotNull @Valid Bildung> bildung,
            @NotNull @Size(max=100) List<@NotNull @Valid Fortbildung> fortbildungen,
            @NotNull @Size(max=100) List<@NotNull @Valid Fahrzeug> dienstwagen,
            @NotNull @Size(max=100) List<@NotNull @Valid Fahrzeug> dienstraeder,
            @NotNull @PositiveOrZero Long version) {
        public static Daten leer() { return new Daten(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L); }
        public Daten mitVersion(long v) { return new Daten(kontakte, vollmachten, kostentraeger, beauftragungen, bildung, fortbildungen, dienstwagen, dienstraeder, v); }
    }
    public record Vollzeitbasis(@NotNull @DecimalMin("0.01") @DecimalMax("168") @Digits(integer=3, fraction=2) BigDecimal wochenstunden,
            @NotNull @PositiveOrZero Long version) {}
    public record Fte(BigDecimal wochenstunden, BigDecimal vollzeitWochenstunden, BigDecimal wert,
            long basisVersion, LocalDate stichtag, boolean wochenstundenBekannt) {}
    public record Akte(Daten daten, Fte fte) {}
    private static String text(String s) { return s == null ? "" : s.strip(); }
}
