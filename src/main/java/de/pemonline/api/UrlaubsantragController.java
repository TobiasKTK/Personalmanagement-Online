package de.pemonline.api;

import de.pemonline.urlaub.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UrlaubsantragController {
    private final UrlaubsantragService service;
    public UrlaubsantragController(UrlaubsantragService service) { this.service = service; }
    @GetMapping("/urlaub/profil") public UrlaubsantragService.Profil profil() { return service.profil(); }
    @GetMapping("/urlaub/antraege/{id}") public UrlaubsantragService.Detail einzelner(@PathVariable long id) { return service.einzelner(id); }
    @GetMapping("/urlaub/antraege/{id}/besetzung") public de.pemonline.planung.BesetzungService.Vorschau besetzung(@PathVariable long id) { return service.besetzung(id); }
    @GetMapping("/urlaub/antraege") public UrlaubsantragRepository.Seite liste(@RequestParam(defaultValue = "false") boolean freigaben,
            @RequestParam(defaultValue = "ALLE") String status, @RequestParam(defaultValue = "0") int seite) { return service.liste(freigaben, status, seite); }
    @PostMapping("/urlaub/antraege") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Urlaubsantrag beantragen(@RequestBody @Valid Urlaubsantrag.Eingabe e) { return service.beantragen(e); }
    @PostMapping("/urlaub/antraege/{id}/entscheidung") public Urlaubsantrag entscheiden(@PathVariable long id, @RequestBody @Valid Urlaubsantrag.Entscheidung e) { return service.entscheiden(id, e); }
    @GetMapping("/mitarbeiter/{person}/urlaubsfreigabe") public UrlaubZuordnung zuordnung(@PathVariable long person) { return service.zuordnung(person); }
    @PutMapping("/mitarbeiter/{person}/urlaubsfreigabe") public UrlaubZuordnung zuordnen(@PathVariable long person, @RequestBody @Valid UrlaubZuordnung.Eingabe e) { return service.zuordnen(person, e); }
}
