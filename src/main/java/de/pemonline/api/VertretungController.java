package de.pemonline.api;
import de.pemonline.planung.VertretungService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/vertretungen")
public class VertretungController {
    private final VertretungService service;
    public VertretungController(VertretungService service) { this.service = service; }
    @GetMapping public List<VertretungService.Eintrag> liste() { return service.liste(); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public VertretungService.Eintrag anlegen(@RequestBody @Valid VertretungService.Eingabe e) { return service.speichern(null, e); }
    @PutMapping("/{id}") public VertretungService.Eintrag aendern(@PathVariable long id, @RequestBody @Valid VertretungService.Eingabe e) { return service.speichern(id, e); }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void loeschen(@PathVariable long id, @RequestParam long version) { service.loeschen(id, version); }
}
