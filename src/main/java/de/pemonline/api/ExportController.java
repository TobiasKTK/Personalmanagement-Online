package de.pemonline.api;
import de.pemonline.export.ExportService;
import de.pemonline.mitarbeiter.MitarbeiterFilter;
import java.time.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class ExportController {
    private final ExportService service;
    private final Clock clock;
    public ExportController(ExportService service, Clock clock) { this.service = service; this.clock = clock; }
    @GetMapping("/export/{datei:mitarbeiter\\.csv|urlaubskonten\\.csv|urlaub\\.ics}")
    public ResponseEntity<byte[]> export(@PathVariable String datei, @RequestParam(defaultValue = "") String suche,
            @RequestParam(required = false) Long organisationseinheitId, @RequestParam(required = false) String beschaeftigung,
            @RequestParam(required = false) LocalDate stichtag, @RequestParam(required = false) String sortierung,
            @RequestParam(required = false) String richtung, @RequestParam(required = false) Integer jahr) {
        if (jahr == null) jahr = LocalDate.now(clock).getYear();
        var f = new MitarbeiterFilter(suche, organisationseinheitId, beschaeftigung, stichtag, sortierung, richtung);
        byte[] daten = switch (datei) { case "mitarbeiter.csv" -> service.mitarbeiter(f); case "urlaubskonten.csv" -> service.urlaubskonten(f, jahr); default -> service.teamUrlaub(f, jahr); };
        return datei(datei, daten);
    }
    @GetMapping("/urlaub/export.ics") public ResponseEntity<byte[]> eigener(@RequestParam int jahr) { return datei("mein-urlaub-" + jahr + ".ics", service.eigenerUrlaub(jahr)); }
    private ResponseEntity<byte[]> datei(String name, byte[] daten) { return ResponseEntity.ok().contentType(MediaType.parseMediaType(name.endsWith(".ics") ? "text/calendar;charset=UTF-8" : "text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString()).cacheControl(CacheControl.noStore()).body(daten); }
}
