package de.pemonline.api;

import de.pemonline.datenimport.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static de.pemonline.datenimport.ImportModel.*;

@RestController
@RequestMapping("/api/importe")
public class ImportController {
    private final ImportService service;
    public ImportController(ImportService service) { this.service = service; }
    @PostMapping("/csv/spalten") public CsvLeser.Tabelle spalten(@Valid @RequestBody Eingabe eingabe) { return service.spalten(eingabe); }
    @PostMapping("/mitarbeiter/vorschau") public Vorschau vorschau(@Valid @RequestBody Eingabe eingabe) { return service.vorschau(eingabe); }
    @PostMapping("/mitarbeiter/{id}/uebernahme") public Ergebnis uebernehmen(@PathVariable UUID id) { return service.uebernehmen(id); }
}
