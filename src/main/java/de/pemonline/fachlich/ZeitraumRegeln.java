package de.pemonline.fachlich;

import java.time.LocalDate;
import static de.pemonline.fachlich.FachlicherFehler.Art.UNGUELTIG;

public final class ZeitraumRegeln {
    private ZeitraumRegeln() {}
    public static void kalenderjahr(LocalDate datum) {
        if (datum != null && (datum.getYear() < 1 || datum.getYear() > 9999))
            throw new FachlicherFehler(UNGUELTIG, "Datumsangaben müssen zwischen den Jahren 0001 und 9999 liegen.");
    }
    public static void beschaeftigung(LocalDate geburt, LocalDate eintritt, LocalDate austritt) {
        kalenderjahr(geburt);
        kalenderjahr(eintritt);
        kalenderjahr(austritt);
        if (eintritt == null) throw new FachlicherFehler(UNGUELTIG, "Bitte ein Eintrittsdatum eingeben.");
        if (geburt != null && geburt.isAfter(eintritt))
            throw new FachlicherFehler(UNGUELTIG, "Das Geburtsdatum darf nicht nach dem Eintrittsdatum liegen.");
        if (austritt != null && austritt.isBefore(eintritt))
            throw new FachlicherFehler(UNGUELTIG, "Das Austrittsdatum darf nicht vor dem Eintrittsdatum liegen.");
    }
    public static void abwesenheit(LocalDate beginn, LocalDate ende, LocalDate eintritt, LocalDate austritt) {
        kalenderjahr(beginn);
        kalenderjahr(ende);
        if (beginn == null || ende == null || ende.isBefore(beginn))
            throw new FachlicherFehler(UNGUELTIG, "Ungültiger Zeitraum. Das Ende darf nicht vor dem Beginn liegen.");
        if (beginn.isBefore(eintritt) || (austritt != null && ende.isAfter(austritt)))
            throw new FachlicherFehler(UNGUELTIG, "Die Abwesenheit muss innerhalb des Beschäftigungszeitraums liegen.");
    }
}
