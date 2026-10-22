package de.pemonline.api;

import de.pemonline.sicherheit.TeamService;
import jakarta.validation.Valid;
import java.time.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TeamController {
    private final TeamService service;
    private final Clock clock;
    public TeamController(TeamService service, Clock clock) { this.service = service; this.clock = clock; }
    @GetMapping("/konten/{konto}/team")
    public TeamService.Zuordnung zuordnung(@PathVariable String konto) { return service.zuordnung(konto); }
    @PutMapping("/konten/{konto}/team")
    public TeamService.Zuordnung speichern(@PathVariable String konto, @Valid @RequestBody TeamService.Eingabe e) { return service.speichern(konto, e); }
    @GetMapping("/team")
    public TeamService.Seite team(@RequestParam(defaultValue = "0") int seite, @RequestParam(defaultValue = "") String suche,
            @RequestParam(required = false) LocalDate von, @RequestParam(required = false) LocalDate bis) {
        var monat = YearMonth.now(clock);
        return service.team(seite, suche, von == null ? monat.atDay(1) : von, bis == null ? monat.atEndOfMonth() : bis);
    }
}
