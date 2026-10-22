package de.pemonline.mitarbeiter;

import java.util.List;

public record MitarbeiterSeite(List<Mitarbeiter> mitarbeiter, long gesamt, int seite, int groesse) {
}
