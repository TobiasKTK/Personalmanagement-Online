package de.pemonline.abwesenheit;

import de.pemonline.fachlich.ZeitraumRegeln;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
public class KalenderService {
    public record Person(long id, String name, long personalnummer, String organisationseinheit, boolean historieBekannt,
                         List<Abwesenheit> abwesenheiten) {}
    public record Seite(LocalDate beginn, LocalDate ende, List<Person> eintraege, long gesamt) {}
    private final KalenderRepository repository;
    public KalenderService(KalenderRepository repository) { this.repository = repository; }
    public Seite monat(@Min(1) @Max(9999) int jahr, @Min(1) @Max(12) int monat,
                       @Positive Long organisation, @Min(0) int seite, @Min(1) @Max(100) int groesse,
                       @NotNull @Size(max = 120) String suche) {
        var zeitraum = YearMonth.of(jahr, monat);
        ZeitraumRegeln.kalenderjahr(zeitraum.atDay(1));
        return repository.monat(zeitraum.atDay(1), zeitraum.atEndOfMonth(), organisation, seite, groesse, suche.strip());
    }
}
