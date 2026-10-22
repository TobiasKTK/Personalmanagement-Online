package de.pemonline.api;

import de.pemonline.benachrichtigung.BenachrichtigungService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/benachrichtigungen")
public class BenachrichtigungController {
    private final BenachrichtigungService service;
    public BenachrichtigungController(BenachrichtigungService service) { this.service = service; }
    @GetMapping public BenachrichtigungService.Seite liste(@RequestParam(defaultValue = "false") boolean ungelesen, @RequestParam(defaultValue = "0") int seite) { return service.liste(ungelesen, seite); }
    public record Anzahl(long ungelesen) {}
    @GetMapping("/anzahl") public Anzahl anzahl() { return new Anzahl(service.ungelesen()); }
    @PutMapping("/{id}/gelesen") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void gelesen(@PathVariable long id) { service.gelesen(id); }
}
