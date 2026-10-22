package de.pemonline.api;

import static de.pemonline.fortbildung.FortbildungsModel.*;
import de.pemonline.fortbildung.FortbildungsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/entwicklung/schulungen")
public class FortbildungsController {
    private final FortbildungsService service;
    public FortbildungsController(FortbildungsService service) { this.service = service; }
    @GetMapping public List<Schulung> liste() { return service.liste(); }
    @GetMapping("/{id}") public Schulung laden(@PathVariable @Positive long id) { return service.laden(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Schulung anlegen(@Valid @RequestBody SchulungsEingabe e) { return service.speichern(null, e); }
    @PutMapping("/{id}") public Schulung aendern(@PathVariable @Positive long id, @Valid @RequestBody SchulungsEingabe e) { return service.speichern(id, e); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void loeschen(@PathVariable @Positive long id, @RequestParam @PositiveOrZero long version) { service.loeschen(id, version); }
    @PostMapping("/{id}/teilnahmen") public Schulung zuordnen(@PathVariable @Positive long id, @Valid @RequestBody Zuordnung e) { return service.zuordnen(id, e); }
    @DeleteMapping("/{id}/teilnahmen/{person}") public Schulung entfernen(@PathVariable @Positive long id, @PathVariable @Positive long person, @RequestParam @PositiveOrZero long version) { return service.teilnahmeEntfernen(id, person, version); }
    @PostMapping("/{id}/teilnahmen/{person}/abschluss") public Schulung abschliessen(@PathVariable @Positive long id, @PathVariable @Positive long person, @Valid @RequestBody Abschluss e) { return service.abschliessen(id, person, e); }
}
