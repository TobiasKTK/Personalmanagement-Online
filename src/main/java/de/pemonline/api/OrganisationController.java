package de.pemonline.api;

import de.pemonline.organisation.*;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/organisationseinheiten")
public class OrganisationController {
    private final OrganisationService service;
    public OrganisationController(OrganisationService service) { this.service = service; }
    @GetMapping public List<Organisation> liste() { return service.liste(); }
    @GetMapping("/revisionen") public List<OrganisationRepository.Revision> revisionen(@RequestParam(defaultValue = "0") @PositiveOrZero int seite) { return service.revisionen(seite); }
    @GetMapping("/revisionen/{id}") public List<Organisation> stand(@PathVariable @Positive long id) { return service.stand(id); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Organisation anlegen(@Valid @RequestBody OrganisationEingabe eingabe) { return service.speichern(null, eingabe); }
    @PutMapping("/{id}") public Organisation aendern(@PathVariable @Positive long id, @Valid @RequestBody OrganisationEingabe eingabe) {
        return service.speichern(id, eingabe);
    }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void loeschen(@PathVariable @Positive long id, @RequestParam @PositiveOrZero long version) { service.loeschen(id, version); }
}
