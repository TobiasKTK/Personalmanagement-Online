package de.pemonline.datenimport;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.fachlich.ZeitraumRegeln;
import de.pemonline.mitarbeiter.MitarbeiterEingabe;
import de.pemonline.organisation.OrganisationService;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import static de.pemonline.fachlich.FachlicherFehler.Art.UNGUELTIG;
import static de.pemonline.datenimport.ImportModel.*;

@Component
public class ImportPruefung {
    private static final Set<String> PFLICHT = Set.of("personalnummer", "vorname", "nachname", "eintrittsdatum", "wochenstunden");
    private static final Set<String> FELDER = Set.of("personalnummer", "titel", "nachname", "vorname", "geburtsname", "kuerzel", "geburtsdatum",
            "eintrittsdatum", "austrittsdatum", "organisationseinheit", "stelle", "beschaeftigungsart", "wochenstunden");
    private final Validator validator;
    private final JdbcClient jdbc;
    private final OrganisationService organisationen;
    public ImportPruefung(Validator validator, JdbcClient jdbc, OrganisationService organisationen) {
        this.validator = validator; this.jdbc = jdbc; this.organisationen = organisationen;
    }
    public List<Zeile> pruefen(Eingabe eingabe, CsvLeser.Tabelle tabelle) {
        var zuordnung = eingabe.zuordnung();
        if (!zuordnung.keySet().containsAll(PFLICHT) || !FELDER.containsAll(zuordnung.keySet())
                || zuordnung.values().stream().anyMatch(i -> i == null || i < 0 || i >= tabelle.spalten().size())
                || new HashSet<>(zuordnung.values()).size() != zuordnung.size())
            throw new FachlicherFehler(UNGUELTIG, "Personalnummer, Vorname, Nachname, Eintritt und Wochenstunden jeweils einer eigenen vorhandenen Spalte zuordnen. Nur angebotene Felder sind erlaubt.");
        Map<String, Long> organisationIds = new HashMap<>();
        organisationen.liste().forEach(o -> organisationIds.put(o.name().strip().toLowerCase(Locale.ROOT), o.id()));
        var zeilen = new ArrayList<Zeile>();
        for (int i = 0; i < tabelle.zeilen().size(); i++) {
            var werte = tabelle.zeilen().get(i);
            var fehler = new ArrayList<String>();
            var leser = new Felder(werte, zuordnung, fehler);
            String organisation = leser.text("organisationseinheit");
            Long organisationId = organisation.isBlank() ? null : organisationIds.get(organisation.toLowerCase(Locale.ROOT));
            if (!organisation.isBlank() && organisationId == null) fehler.add("Organisationseinheit nicht vorhanden: " + organisation);
            var daten = new MitarbeiterEingabe(leser.nummer(), leser.text("titel"), leser.text("nachname"), leser.text("vorname"),
                    leser.text("geburtsname"), leser.text("kuerzel"), leser.datum("geburtsdatum"), leser.datum("eintrittsdatum"),
                    leser.datum("austrittsdatum"), organisation, leser.text("stelle"), leser.text("beschaeftigungsart"),
                    leser.stunden(), null, organisationId, null);
            validator.validate(daten).stream().map(v -> v.getMessage()).sorted().forEach(fehler::add);
            try { ZeitraumRegeln.beschaeftigung(daten.geburtsdatum(), daten.eintrittsdatum(), daten.austrittsdatum()); }
            catch (FachlicherFehler e) { fehler.add(e.getMessage()); }
            zeilen.add(new Zeile(i + 2, daten, fehler));
        }
        var nummern = new HashMap<Long, Integer>();
        zeilen.stream().map(z -> z.daten().personalnummer()).filter(Objects::nonNull).forEach(n -> nummern.merge(n, 1, (vorhandenAnzahl, hinzugekommen) -> vorhandenAnzahl + hinzugekommen));
        Set<Long> vorhanden = nummern.isEmpty() ? Set.of() : new HashSet<>(jdbc.sql("SELECT personalnummer FROM mitarbeiter WHERE personalnummer IN (:nummern)")
                .param("nummern", nummern.keySet()).query(Long.class).list());
        for (var z : zeilen) {
            Long nummer = z.daten().personalnummer();
            if (nummer != null && nummern.get(nummer) > 1) z.fehler().add("Personalnummer kommt mehrfach in der CSV vor.");
            if (nummer != null && vorhanden.contains(nummer)) z.fehler().add("Personalnummer existiert bereits in dieser Firma. Bestehende Personen werden nicht überschrieben.");
        }
        return zeilen.stream().map(z -> new Zeile(z.nummer(), z.daten(), z.fehler().stream().distinct().toList())).toList();
    }
    private record Felder(List<String> werte, Map<String, Integer> zuordnung, List<String> fehler) {
        String text(String feld) { return zuordnung.containsKey(feld) ? werte.get(zuordnung.get(feld)).strip() : ""; }
        Long nummer() {
            try { String n = text("personalnummer"); if (!n.matches("[0-9]+")) throw new NumberFormatException(); return Long.valueOf(n); }
            catch (NumberFormatException e) { fehler.add("Personalnummer muss eine positive ganze Zahl sein."); return null; }
        }
        LocalDate datum(String feld) {
            String wert = text(feld); if (wert.isBlank()) return null;
            try { if (!wert.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException(); return LocalDate.parse(wert); }
            catch (RuntimeException e) { fehler.add(feld + ": gültiges Datum im Format JJJJ-MM-TT verwenden."); return null; }
        }
        BigDecimal stunden() {
            try { String wert = text("wochenstunden"); if (!wert.matches("[0-9]+([.,][0-9]{1,2})?")) throw new NumberFormatException(); return new BigDecimal(wert.replace(',', '.')); }
            catch (NumberFormatException e) { fehler.add("Wochenstunden als Zahl mit höchstens zwei Nachkommastellen angeben."); return null; }
        }
    }
}
