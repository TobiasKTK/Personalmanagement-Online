package de.pemonline.auswertung;

import static de.pemonline.auswertung.AuswertungModel.*;
import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.fachlich.ZeitraumRegeln;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;

@Service @Validated
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
public class AuswertungService {
    private static final int SEITENGROESSE = 20;
    private static final List<String> HINWEISE = List.of(
        "Beschäftigung, Organisationszuordnung und Wochenstunden gelten zum Stichtag. Organisationsnamen entsprechen dem aktuellen Katalog.",
        "FTE verwendet die aktuelle Vollzeitbasis der Firma. Bei unbekannter Historie zählen Personen, aber keine geschätzten Stunden oder FTE. Der Organisationsfilter schließt unbekannte Zuordnungen aus.",
        "Planstellen, Stellenprofile und Besetzungen sind aktuell. Offene Stellen sind derzeit keiner Person zugeordnet und werden nach ihrer aktuellen Organisation gefiltert.",
        "Qualifikationslücken: am Stichtag Beschäftigte, damalige Organisation, höchste Anforderung der heute zugeordneten Planstellen. Nachweise stammen aus dem aktuellen Datenbestand; ihre Gültigkeit wird zum Stichtag geprüft.");
    private final AuswertungRepository repository;
    private final Clock clock;
    public AuswertungService(AuswertungRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    public Uebersicht laden(LocalDate stichtag, @Positive Long organisationseinheitId,
            @PositiveOrZero int organisationsSeite, @PositiveOrZero int stellenSeite, @PositiveOrZero int bedarfsSeite) {
        LocalDate tag = pruefen(stichtag, organisationseinheitId);
        BigDecimal basis = repository.vollzeitbasis();
        var summe = repository.summe(tag, organisationseinheitId);
        return new Uebersicht(tag, organisationseinheitId, basis, summe.beschaeftigte(), summe.wochenstunden(), AuswertungRepository.fte(summe.wochenstunden(), basis),
            repository.unbekannt(tag),
            new Seite<>(repository.organisationen(tag, organisationseinheitId, basis, (long) organisationsSeite * SEITENGROESSE, SEITENGROESSE), repository.organisationenAnzahl(tag, organisationseinheitId), organisationsSeite, SEITENGROESSE),
            new Seite<>(repository.stellen(organisationseinheitId, (long) stellenSeite * SEITENGROESSE, SEITENGROESSE), repository.stellenAnzahl(organisationseinheitId), stellenSeite, SEITENGROESSE),
            new Seite<>(repository.bedarf(tag, organisationseinheitId, (long) bedarfsSeite * SEITENGROESSE, SEITENGROESSE), repository.bedarfAnzahl(tag, organisationseinheitId), bedarfsSeite, SEITENGROESSE), HINWEISE);
    }

    public byte[] exportieren(LocalDate stichtag, @Positive Long organisationseinheitId) {
        LocalDate tag = pruefen(stichtag, organisationseinheitId);
        BigDecimal basis = repository.vollzeitbasis();
        var out = new StringBuilder("\uFEFF");
        zeile(out, "Typ", "Stichtag", "Organisationsfilter-ID", "Vollzeitbasis (Stunden)", "Organisation", "Personalnummer", "Name / Bezeichnung", "Beschäftigte", "Wochenstunden", "FTE / Stellenanteil", "Profil / Kenntnis", "Soll", "Ist", "Differenz", "Historie bekannt", "Hinweis");
        var summe = repository.summe(tag, organisationseinheitId);
        zeile(out, "SUMME", tag, organisationseinheitId, zahl(basis), "", "", "", summe.beschaeftigte(), zahl(summe.wochenstunden()), zahl(AuswertungRepository.fte(summe.wochenstunden(), basis)), "", "", "", "", "", "Historie unbekannt in der Firma: " + repository.unbekannt(tag));
        for (String hinweis : HINWEISE) zeile(out, "HINWEIS", tag, organisationseinheitId, zahl(basis), "", "", "", "", "", "", "", "", "", "", "", hinweis);
        alle(offset -> repository.organisationen(tag, organisationseinheitId, basis, offset, 500), o ->
            zeile(out, "ORGANISATION", tag, organisationseinheitId, zahl(basis), o.organisation(), "", "", o.beschaeftigte(), zahl(o.wochenstunden()), zahl(o.fte()), "", "", "", "", o.historieBekannt() ? "Ja" : "Nein", ""));
        alle(offset -> repository.stellen(organisationseinheitId, offset, 500), s ->
            zeile(out, "PLANSTELLE", tag, organisationseinheitId, zahl(basis), s.organisation(), "", s.bezeichnung(), "", "", zahl(s.anteil()), s.profil(), "", "", "", "", "Aktuell unbesetzt"));
        alle(offset -> repository.bedarf(tag, organisationseinheitId, offset, 500), b ->
            zeile(out, "QUALIFIKATION", tag, organisationseinheitId, zahl(basis), b.organisation(), b.personalnummer(), b.name(), "", "", "", b.kenntnis(), b.soll(), b.ist(), b.differenz(), b.historieBekannt() ? "Ja" : "Nein", b.grund()));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private LocalDate pruefen(LocalDate stichtag, Long org) {
        LocalDate tag = stichtag == null ? LocalDate.now(clock) : stichtag;
        ZeitraumRegeln.kalenderjahr(tag);
        if (org != null && !repository.organisationExistiert(org)) throw new FachlicherFehler(FachlicherFehler.Art.NICHT_GEFUNDEN, "Organisationseinheit nicht gefunden.");
        return tag;
    }
    private static <T> void alle(LongFunction<List<T>> laden, Consumer<T> schreiben) {
        for (long offset = 0;; offset += 500) {
            List<T> seite = laden.apply(offset);
            seite.forEach(schreiben);
            if (seite.size() < 500) return;
        }
    }
    private static String zahl(BigDecimal n) { return n == null ? "" : n.stripTrailingZeros().toPlainString().replace('.', ','); }
    private static void zeile(StringBuilder out, Object... werte) {
        out.append(Arrays.stream(werte).map(AuswertungService::zelle).collect(Collectors.joining(";"))).append("\r\n");
    }
    private static String zelle(Object wert) {
        String text = wert == null ? "" : wert.toString();
        String start = text.stripLeading();
        if ((!start.isEmpty() && "=+-@".indexOf(start.charAt(0)) >= 0) || (!text.isEmpty() && Character.isISOControl(text.charAt(0)))) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
