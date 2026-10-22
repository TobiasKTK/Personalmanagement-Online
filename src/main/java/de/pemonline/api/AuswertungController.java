package de.pemonline.api;

import de.pemonline.auswertung.AuswertungService;
import de.pemonline.auswertung.AuswertungModel.Uebersicht;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api/auswertungen")
public class AuswertungController {
    private final AuswertungService service;
    public AuswertungController(AuswertungService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<Uebersicht> laden(@RequestParam(required = false) LocalDate stichtag,
            @RequestParam(required = false) @Positive Long organisationseinheitId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int organisationsSeite,
            @RequestParam(defaultValue = "0") @PositiveOrZero int stellenSeite,
            @RequestParam(defaultValue = "0") @PositiveOrZero int bedarfsSeite) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.laden(stichtag, organisationseinheitId, organisationsSeite, stellenSeite, bedarfsSeite));
    }
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportieren(@RequestParam(required = false) LocalDate stichtag,
            @RequestParam(required = false) @Positive Long organisationseinheitId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("hr-auswertung.csv").build().toString())
                .cacheControl(CacheControl.noStore()).body(service.exportieren(stichtag, organisationseinheitId));
    }
}
