package de.pemonline.organisation;
import jakarta.validation.constraints.*;
public record OrganisationEingabe(
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cntrl}]*$") String name,
        @PositiveOrZero Long version, @Positive Long parentId) {
    public OrganisationEingabe(String name, Long version) { this(name, version, null); }
    public OrganisationEingabe { name = name == null ? "" : name.strip(); }
}
