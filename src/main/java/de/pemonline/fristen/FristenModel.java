package de.pemonline.fristen;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class FristenModel {
    private FristenModel() {}
    public record Frist(long id, long mitarbeiterId, String person, String art, String titel, LocalDate ablauf,
            String ziel, String zustaendig, int vorlaufTage, LocalDate wiedervorlage, boolean erledigt, long version) {}
    public record Eingabe(@Size(max=80) String zustaendig, @NotNull @Min(0) @Max(3650) Integer vorlaufTage,
            LocalDate wiedervorlage, @NotNull Boolean erledigt, @NotNull @PositiveOrZero Long version) {}
    public record Zustaendig(String name) {}
}
