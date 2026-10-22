package de.pemonline.api;

import de.pemonline.mitarbeiter.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mitarbeiter/{person}/historie")
public class HistorieController {
    private final HistorieService service;
    public HistorieController(HistorieService service) { this.service = service; }
    @GetMapping public HistorieService.Seite liste(@PathVariable long person, @RequestParam(defaultValue = "0") int seite) { return service.liste(person, seite); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public HistorieRepository.Eintrag anlegen(@PathVariable long person, @RequestBody @Valid HistorieService.Eingabe e) { return service.speichern(person, null, e); }
    @PutMapping("/{id}") public HistorieRepository.Eintrag aendern(@PathVariable long person, @PathVariable long id, @RequestBody @Valid HistorieService.Eingabe e) { return service.speichern(person, id, e); }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void loeschen(@PathVariable long person, @PathVariable long id, @RequestParam long version, @RequestParam long mitarbeiterVersion) { service.loeschen(person, id, version, mitarbeiterVersion); }
}
