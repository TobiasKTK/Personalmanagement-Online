package de.pemonline.entwicklung;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class EntwicklungModel {
    private EntwicklungModel() {}
    public enum Kategorie { FACHKENNTNIS, KOMPETENZ, SPRACHE, IT, ZERTIFIKAT, AUSBILDUNG, PFLICHTFORTBILDUNG }
    public enum Ausbildungsstatus { GEPLANT, AKTIV, ABGESCHLOSSEN, ABGEBROCHEN }
    public enum Stationsstatus { GEPLANT, AKTIV, ABGESCHLOSSEN }
    public record Person(long id, String name, long personalnummer) {}
    public record Kenntnis(long id, String name, Kategorie kategorie, long version) {}
    public record KenntnisEingabe(@NotBlank @Size(max=120) String name, @NotNull Kategorie kategorie, @PositiveOrZero Long version) {
        public KenntnisEingabe { name = sauber(name); }
    }
    public record Anforderung(@Positive long kenntnisId, @Min(1) @Max(5) int niveau) {}
    public record Profil(long id, String name, String beschreibung, String aufgaben, long version, List<Anforderung> anforderungen) {}
    public record ProfilEingabe(@NotBlank @Size(max=120) String name, @NotNull @Size(max=4000) String beschreibung,
            @NotNull @Size(max=4000) String aufgaben, @NotNull @Size(max=200) List<@NotNull @Valid Anforderung> anforderungen,
            @PositiveOrZero Long version) { public ProfilEingabe { name = sauber(name); } }
    public record Planstelle(long id, String bezeichnung, long organisationId, long profilId, BigDecimal anteil, Long mitarbeiterId, long version) {}
    public record PlanstellenEingabe(@NotBlank @Size(max=120) String bezeichnung, @Positive long organisationId, @Positive long profilId,
            @NotNull @DecimalMin("0.01") @DecimalMax("1.00") @Digits(integer=1, fraction=2) BigDecimal anteil,
            @Positive Long mitarbeiterId, @PositiveOrZero Long version) { public PlanstellenEingabe { bezeichnung = sauber(bezeichnung); } }
    public record Nachweis(long id, long mitarbeiterId, long kenntnisId, int niveau, LocalDate erworbenAm, LocalDate gueltigBis, String nachweis, long version) {}
    public record NachweisEingabe(@Positive long mitarbeiterId, @Positive long kenntnisId, @Min(1) @Max(5) int niveau,
            @NotNull LocalDate erworbenAm, LocalDate gueltigBis, @NotNull @Size(max=1000) String nachweis, @PositiveOrZero Long version) {}
    public record Abgleich(long kenntnisId, String name, Kategorie kategorie, int soll, int ist, int differenz, String grund) {}
    public record Ausbildung(long id, long mitarbeiterId, String beruf, String institution, Long ausbilderId, LocalDate beginn, LocalDate ende, Ausbildungsstatus status, long version) {}
    public record AusbildungEingabe(@Positive long mitarbeiterId, @NotBlank @Size(max=120) String beruf,
            @NotNull @Size(max=240) String institution, @Positive Long ausbilderId, @NotNull LocalDate beginn, @NotNull LocalDate ende,
            @NotNull Ausbildungsstatus status, @PositiveOrZero Long version) { public AusbildungEingabe { beruf = sauber(beruf); } }
    public record Station(long id, long ausbildungId, String titel, String lernziele, Long organisationId,
            LocalDate beginn, LocalDate ende, Stationsstatus status, long version) {}
    public record StationEingabe(@Positive long ausbildungId, @NotBlank @Size(max=120) String titel,
            @NotBlank @Size(max=4000) String lernziele, @Positive Long organisationId, @NotNull LocalDate beginn, @NotNull LocalDate ende,
            @NotNull Stationsstatus status, @PositiveOrZero Long version) { public StationEingabe { titel = sauber(titel); } }
    private static String sauber(String s) { return s == null ? null : s.strip(); }
}
