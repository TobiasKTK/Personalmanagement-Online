package de.pemonline.api;

import de.pemonline.personaldaten.PersonaldatenService;
import static de.pemonline.personaldaten.PersonaldatenModel.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/mitarbeiter/{person}/personaldaten")
public class PersonaldatenController {
    private final PersonaldatenService service;
    public PersonaldatenController(PersonaldatenService service) { this.service = service; }
    @GetMapping public Akte lesen(@PathVariable @Positive long person) { return service.lesen(person); }
    @GetMapping("/stellenzuordnung") public StellenzuordnungStand stellenzuordnung(@PathVariable @Positive long person) { return service.stellenzuordnung(person); }
    @PutMapping public Akte speichern(@PathVariable @Positive long person, @RequestBody @Valid Daten e) { return service.speichern(person, e); }
    @GetMapping("/historie") public Historie historie(@PathVariable @Positive long person, @RequestParam(defaultValue="0") @Min(0) int seite) { return service.historie(person, seite); }
    @PostMapping("/beschaeftigungszeiten") @ResponseStatus(HttpStatus.CREATED)
    public Akte zeit(@PathVariable @Positive long person, @RequestBody @Valid ZeitEingabe e) { return service.fruehereZeit(person, e); }
    @DeleteMapping("/beschaeftigungszeiten/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void zeitLoeschen(@PathVariable @Positive long person, @PathVariable @Positive long id, @RequestParam @PositiveOrZero long version) { service.zeitLoeschen(person, id, version); }
    @PostMapping("/wiedereintritt") public Akte wiedereintritt(@PathVariable @Positive long person, @RequestBody @Valid Wiedereintritt e) { return service.wiedereintritt(person, e); }
}
