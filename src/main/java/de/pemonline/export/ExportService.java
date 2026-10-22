package de.pemonline.export;

import de.pemonline.mitarbeiter.*;
import de.pemonline.urlaub.*;
import de.pemonline.fachlich.FachlicherFehler;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;

@Service @Validated @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class ExportService {
    private final MitarbeiterRepository mitarbeiter;
    private final UrlaubskontoService konten;
    private final UrlaubsantragRepository antraege;
    private final JdbcClient jdbc;
    private final Clock clock;
    public ExportService(MitarbeiterRepository mitarbeiter, UrlaubskontoService konten, UrlaubsantragRepository antraege, JdbcClient jdbc, Clock clock) {
        this.mitarbeiter = mitarbeiter; this.konten = konten; this.antraege = antraege; this.jdbc = jdbc; this.clock = clock;
    }
    public byte[] mitarbeiter(MitarbeiterFilter filter) {
        var out = new StringBuilder("\uFEFF");
        zeile(out, "Personalnummer", "Name", "Organisationseinheit", "Wochenstunden", "Eintritt", "Austritt", "Stelle", "Beschäftigungsart", "Stichtag", "Historie bekannt");
        alle(filter, m -> zeile(out, m.personalnummer(), m.name(), m.organisationseinheit(), m.historieBekannt() ? zahl(m.wochenstunden()) : "", m.eintrittsdatum(), m.austrittsdatum(), m.stelle(), m.beschaeftigungsart(), m.stichtag(), m.historieBekannt() ? "Ja" : "Nein"));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    public byte[] urlaubskonten(MitarbeiterFilter filter, @Min(1) @Max(9999) int jahr) {
        var out = new StringBuilder("\uFEFF");
        zeile(out, "Personalnummer", "Name", "Organisationseinheit", "Jahr", "Eingerichtet", "Anspruch", "Übertrag", "Genommen", "Geplant", "Beantragt", "Resturlaub", "Verfügbar", "Stichtag", "Historie bekannt");
        alle(filter, m -> {
            var k = konten.konto(m.id(), jahr);
            if (k.eingerichtet()) zeile(out, m.personalnummer(), m.name(), m.organisationseinheit(), jahr, "Ja", zahl(k.anspruch()), zahl(k.uebertrag()), zahl(k.genommen()), zahl(k.geplant()), zahl(k.beantragt()), zahl(k.rest()), zahl(k.verfuegbar()), m.stichtag(), m.historieBekannt() ? "Ja" : "Nein");
            else zeile(out, m.personalnummer(), m.name(), m.organisationseinheit(), jahr, "Nein", "", "", "", "", "", "", "", m.stichtag(), m.historieBekannt() ? "Ja" : "Nein");
        });
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    @PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
    public byte[] eigenerUrlaub(@Min(1) @Max(9999) int jahr) {
        String konto = SecurityContextHolder.getContext().getAuthentication().getName();
        var profil = antraege.eigenesProfil(konto).orElseThrow(() -> new FachlicherFehler(FachlicherFehler.Art.KONFLIKT, "Das Konto ist keinem Mitarbeiter zugeordnet."));
        return kalender(List.of(profil.mitarbeiterId()), jahr);
    }
    public byte[] teamUrlaub(MitarbeiterFilter filter, @Min(1) @Max(9999) int jahr) {
        var ids = new ArrayList<Long>(); alle(filter, m -> ids.add(m.id())); return kalender(ids, jahr);
    }
    private byte[] kalender(List<Long> personen, int jahr) {
        var out = new StringBuilder();
        for (String line : List.of("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Personalmanagement Online//Urlaub//DE", "CALSCALE:GREGORIAN")) icsZeile(out, line);
        String jetzt = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant());
        for (long person : personen) {
            for (var a : jdbc.sql("SELECT a.*, m.name FROM urlaubsantrag a JOIN mitarbeiter m ON m.id = a.mitarbeiter_id WHERE a.mitarbeiter_id = :person AND a.status = 'GENEHMIGT' AND a.beginn <= :bis AND a.ende >= :von ORDER BY a.beginn, a.id")
                    .param("person", person).param("von", LocalDate.of(jahr, 1, 1)).param("bis", LocalDate.of(jahr, 12, 31)).query(Urlaubsantrag.class).list()) {
                long mandant = de.pemonline.mandant.MandantenKontext.id();
                icsZeile(out, "BEGIN:VEVENT"); icsZeile(out, "UID:" + (mandant == 1 ? "" : "mandant-" + mandant + "-") + "urlaub-" + a.id() + "@pemonline.local");
                icsZeile(out, "DTSTAMP:" + jetzt); icsZeile(out, "SEQUENCE:" + a.version());
                icsZeile(out, "DTSTART;VALUE=DATE:" + a.beginn().format(DateTimeFormatter.BASIC_ISO_DATE));
                if (a.ende().equals(LocalDate.of(9999, 12, 31))) icsZeile(out, "DURATION:P" + (java.time.temporal.ChronoUnit.DAYS.between(a.beginn(), a.ende()) + 1) + "D");
                else icsZeile(out, "DTEND;VALUE=DATE:" + a.ende().plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE));
                icsZeile(out, "SUMMARY:" + icsText("Urlaub" + (a.halberTag() ? " (halber Tag)" : "") + " · " + a.name()));
                if (a.halberTag()) icsZeile(out, "DESCRIPTION:Halber Urlaubstag ohne festgelegte Tageszeit.");
                icsZeile(out, "STATUS:CONFIRMED"); icsZeile(out, "CLASS:PRIVATE"); icsZeile(out, "END:VEVENT");
            }
        }
        icsZeile(out, "END:VCALENDAR"); return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void alle(MitarbeiterFilter filter, Consumer<Mitarbeiter> consumer) {
        var f = filter.mitStandardStichtag(LocalDate.now(clock));
        for (int seite = 0;; seite++) { var liste = mitarbeiter.suchen(f, seite, 100); liste.forEach(consumer); if (liste.size() < 100) break; }
    }
    private static String zahl(double n) { return zahl(BigDecimal.valueOf(n)); }
    private static String zahl(BigDecimal n) { return n.stripTrailingZeros().toPlainString().replace('.', ','); }
    private static void zeile(StringBuilder out, Object... werte) { out.append(Arrays.stream(werte).map(ExportService::zelle).collect(Collectors.joining(";"))).append("\r\n"); }
    static String zelle(Object wert) {
        String s = wert == null ? "" : wert.toString();
        String start = s.stripLeading();
        if ((!start.isEmpty() && "=+-@".indexOf(start.charAt(0)) >= 0) || (!s.isEmpty() && Character.isISOControl(s.charAt(0)))) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    static String icsText(String s) { return s.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,"); }
    // RFC 5545: CRLF, maximal 75 UTF-8-Bytes pro physischer Zeile, keine Trennung innerhalb eines Codepoints.
    static void icsZeile(StringBuilder out, String line) {
        int bytes = 0;
        for (int cp : line.codePoints().toArray()) {
            String s = new String(Character.toChars(cp)); int laenge = s.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + laenge > 75) { out.append("\r\n "); bytes = 1; }
            out.append(s); bytes += laenge;
        }
        out.append("\r\n");
    }
}
