package de.pemonline.api;
import de.pemonline.planung.FeiertagskalenderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/feiertagskalender")
public class FeiertagskalenderController {
    private final FeiertagskalenderService service;
    public FeiertagskalenderController(FeiertagskalenderService service) { this.service = service; }
    @GetMapping public List<FeiertagskalenderService.Kalender> liste(@RequestParam(required = false) Integer jahr) { return service.liste(jahr); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public FeiertagskalenderService.Kalender anlegen(@RequestBody @Valid FeiertagskalenderService.Eingabe e) { return service.speichern(null, e); }
    @PutMapping("/{id}") public FeiertagskalenderService.Kalender aendern(@PathVariable long id, @RequestBody @Valid FeiertagskalenderService.Eingabe e) { return service.speichern(id, e); }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void loeschen(@PathVariable long id, @RequestParam long version) { service.loeschen(id, version); }
}
