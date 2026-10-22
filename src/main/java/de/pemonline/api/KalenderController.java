package de.pemonline.api;

import de.pemonline.abwesenheit.KalenderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/kalender")
public class KalenderController {
    private final KalenderService service;
    public KalenderController(KalenderService service) { this.service = service; }
    @GetMapping public KalenderService.Seite monat(@RequestParam int jahr, @RequestParam int monat,
            @RequestParam(required = false) Long organisationseinheitId,
            @RequestParam(defaultValue = "0") int seite, @RequestParam(defaultValue = "20") int groesse,
            @RequestParam(defaultValue = "") String suche) {
        return service.monat(jahr, monat, organisationseinheitId, seite, groesse, suche);
    }
}
