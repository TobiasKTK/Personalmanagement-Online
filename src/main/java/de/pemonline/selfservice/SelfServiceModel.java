package de.pemonline.selfservice;

import de.pemonline.personaldaten.PersonaldatenModel.Bank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class SelfServiceModel {
    private SelfServiceModel() {}
    public enum Art { KONTAKT, BANK }
    public enum Status { BEANTRAGT, GENEHMIGT, ABGELEHNT }
    public record Kontakt(@Size(max=80) @Pattern(regexp="[0-9+() ./-]*") String telefon, @Email @Size(max=254) String email) {
        public Kontakt { telefon = telefon == null ? "" : telefon.strip(); email = email == null ? "" : email.strip(); }
    }
    public record Profil(long mitarbeiterId, String name, Kontakt kontakt, long kontaktVersion, Bank bank, long bankVersion) {}
    public record Eingabe(@NotNull @Positive Long mitarbeiterId, @NotNull Art art, @Valid Kontakt kontakt, @Valid Bank bank, @NotNull @PositiveOrZero Long bestandsversion) {}
    public record Entscheidung(@NotNull Status status, @Size(max=500) String grund, @NotNull @PositiveOrZero Long version) {
        public Entscheidung { grund = grund == null ? "" : grund.strip(); }
    }
    public record Antrag(long id, long mitarbeiterId, String name, String antragsteller, Art art, Object vorher, Object nachher,
            long bestandsversion, Status status, String grund, Instant erstellt, Instant entschieden, String bearbeiter, long version, boolean entscheidbar) {}
    public record Seite(List<Antrag> eintraege, long gesamt) {}
}
