package de.pemonline;

import java.math.BigDecimal;
import java.time.LocalDate;
import de.pemonline.mitarbeiter.MitarbeiterEingabe;

final class Testdaten {
    private Testdaten() {}

    static MitarbeiterEingabe eingabe(String nachname, Long version) {
        return new MitarbeiterEingabe(null, "", nachname, "Test", "", "", null,
                LocalDate.of(2026, 10, 5), null, "", "", "", BigDecimal.valueOf(40), version, null,
                version == null ? null : LocalDate.of(2026, 10, 5));
    }
}
