package de.pemonline.datenimport;

import de.pemonline.mitarbeiter.MitarbeiterEingabe;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ImportModel {
    private ImportModel() {}
    public record Eingabe(@NotBlank @Size(max=1048576) String csv, @NotNull @Size(min=1,max=1) String trennzeichen,
            @NotNull @Size(max=13) Map<String, Integer> zuordnung) {}
    public record Zeile(int nummer, MitarbeiterEingabe daten, List<String> fehler) {}
    public record Vorschau(String id, Instant gueltigBis, List<Zeile> zeilen, int fehleranzahl) {}
    public record Ergebnis(String id, int importiert) {}
    public record Auftrag(String id, String kontoname, Instant erstelltAm, String inhalt, int anzahl, boolean uebernommen) {}
}
