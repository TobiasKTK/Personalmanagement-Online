package de.pemonline.urlaub;

import java.time.*;
import jakarta.validation.constraints.*;

public record Urlaubsantrag(long id, long mitarbeiterId, String name, String antragsteller,
        LocalDate beginn, LocalDate ende, boolean halberTag, String status, String notiz, String entscheidung,
        String bearbeiter, OffsetDateTime erstellt, OffsetDateTime entschieden, Long abwesenheitId, long version) {
    public record Eingabe(@NotNull LocalDate beginn, @NotNull LocalDate ende, boolean halberTag, @Size(max = 500) String notiz) {}
    public record Entscheidung(@NotNull @Pattern(regexp = "GENEHMIGEN|ABLEHNEN|ZURUECKZIEHEN|STORNIEREN") String aktion,
                               @NotNull @PositiveOrZero Long version, @Size(max = 500) String begruendung, Boolean warnungBestaetigt) {
        public Entscheidung(String aktion, Long version, String begruendung) { this(aktion, version, begruendung, false); }
    }
}
