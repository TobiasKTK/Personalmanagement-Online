package de.pemonline.api;

import de.pemonline.urlaub.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mitarbeiter/{person}/urlaubskonto/{jahr}")
public class UrlaubskontoController {
    private final UrlaubskontoService service;
    public UrlaubskontoController(UrlaubskontoService service) { this.service = service; }
    @GetMapping public Urlaubskonto konto(@PathVariable long person, @PathVariable int jahr) { return service.konto(person, jahr); }
    @PutMapping public Urlaubskonto speichern(@PathVariable long person, @PathVariable int jahr, @RequestBody @Valid Urlaubskonto.Eingabe e) { return service.speichern(person, jahr, e); }
}
