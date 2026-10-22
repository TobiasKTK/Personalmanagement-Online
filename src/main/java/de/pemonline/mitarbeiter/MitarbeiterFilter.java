package de.pemonline.mitarbeiter;

import java.time.LocalDate;
import de.pemonline.fachlich.FachlicherFehler;

public record MitarbeiterFilter(String suche, Long organisationseinheitId, String beschaeftigung,
        LocalDate stichtag, String sortierung, String richtung) {

    public MitarbeiterFilter {
        suche = suche == null ? "" : suche.strip();
        beschaeftigung = beschaeftigung == null ? "ALLE" : beschaeftigung;
        sortierung = sortierung == null ? "nachname" : sortierung;
        richtung = richtung == null ? "ASC" : richtung;
        if (suche.length() > 120 || (organisationseinheitId != null && organisationseinheitId < 1)
                || !java.util.Set.of("ALLE", "AKTIV", "KUENFTIG", "AUSGESCHIEDEN").contains(beschaeftigung)
                || !java.util.Set.of("nachname", "personalnummer", "eintrittsdatum", "wochenstunden", "organisationseinheit").contains(sortierung) || !java.util.Set.of("ASC", "DESC").contains(richtung)
                || (stichtag != null && (stichtag.getYear() < 1 || stichtag.getYear() > 9999))) {
            throw new FachlicherFehler(FachlicherFehler.Art.UNGUELTIG, "Ungültige Filter oder Sortierung.");
        }
    }

    public static MitarbeiterFilter standard() { return new MitarbeiterFilter(null, null, null, null, null, null); }

    public MitarbeiterFilter mitStandardStichtag(LocalDate heute) {
        return stichtag == null ? new MitarbeiterFilter(suche, organisationseinheitId, beschaeftigung, heute, sortierung, richtung) : this;
    }
}
