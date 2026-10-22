package de.pemonline.api;

import de.pemonline.personaldaten.ErweitertePersonaldatenService;
import static de.pemonline.personaldaten.ErweitertePersonaldatenModel.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/mitarbeiter/{person}/personaldaten/erweiterungen")
public class ErweitertePersonaldatenController {
    private final ErweitertePersonaldatenService service;
    public ErweitertePersonaldatenController(ErweitertePersonaldatenService service) { this.service = service; }
    @GetMapping public Akte lesen(@PathVariable @Positive long person) { return service.lesen(person); }
    @PutMapping public Akte speichern(@PathVariable @Positive long person, @RequestBody @Valid Daten e) { return service.speichern(person, e); }
    @PutMapping("/vollzeitbasis") public Akte basis(@PathVariable @Positive long person, @RequestBody @Valid Vollzeitbasis e) { return service.vollzeitbasis(person, e); }
}
