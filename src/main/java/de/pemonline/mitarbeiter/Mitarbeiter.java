package de.pemonline.mitarbeiter;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Mitarbeiter(
        long id, String name, BigDecimal wochenstunden, LocalDate eintrittsdatum, long version,
        long personalnummer, String titel, String nachname, String vorname, String geburtsname,
        String kuerzel, LocalDate geburtsdatum, LocalDate austrittsdatum, String organisationseinheit,
        String stelle, String beschaeftigungsart, Long organisationseinheitId,
        String altBeschaeftigungsstatus, String altAbwesenheitsstatus,
        String beschaeftigung, String abwesenheitAmStichtag, LocalDate stichtag,
        boolean historieBekannt, LocalDate datenGueltigAb) {
}
