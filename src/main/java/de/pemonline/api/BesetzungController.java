package de.pemonline.api;
import de.pemonline.planung.BesetzungService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/besetzungsregeln")
public class BesetzungController {
    private final BesetzungService service;
    public BesetzungController(BesetzungService service) { this.service = service; }
    @GetMapping public List<BesetzungService.Regel> liste() { return service.liste(); }
    @PutMapping("/{organisation}") public BesetzungService.Regel speichern(@PathVariable long organisation, @RequestBody @Valid BesetzungService.Eingabe e) { return service.speichern(organisation, e); }
    @DeleteMapping("/{organisation}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void loeschen(@PathVariable long organisation, @RequestParam long version) { service.loeschen(organisation, version); }
}
