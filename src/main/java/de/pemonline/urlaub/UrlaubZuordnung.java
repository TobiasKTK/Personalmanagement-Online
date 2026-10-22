package de.pemonline.urlaub;

import jakarta.validation.constraints.*;

public record UrlaubZuordnung(long mitarbeiterId, String konto, String modus, String genehmiger, Long version) {
    public record Eingabe(@Pattern(regexp = "[a-z0-9._-]{3,80}") String konto,
            @NotNull @Pattern(regexp = "ZUGEORDNET|ALLE") String modus,
            @Pattern(regexp = "[a-z0-9._-]{3,80}") String genehmiger, @PositiveOrZero Long version) {}
}
