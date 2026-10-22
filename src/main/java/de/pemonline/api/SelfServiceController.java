package de.pemonline.api;

import de.pemonline.selfservice.SelfServiceService;
import de.pemonline.personaldaten.PersonaldokumentService;
import static de.pemonline.selfservice.SelfServiceModel.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/self-service")
public class SelfServiceController {
    private final SelfServiceService service;
    public SelfServiceController(SelfServiceService service) { this.service=service; }
    @GetMapping("/profil") public Profil profil() { return service.profil(); }
    @GetMapping("/antraege") public Seite antraege(@RequestParam(defaultValue="0") @Min(0) int seite) { return service.eigeneAntraege(seite); }
    @GetMapping("/pruefung") public Seite pruefung(@RequestParam(defaultValue="0") @Min(0) int seite) { return service.pruefung(seite); }
    @GetMapping("/antraege/{id}") public Antrag antrag(@PathVariable @Positive long id) { return service.einzelner(id); }
    @PostMapping("/antraege") @ResponseStatus(HttpStatus.CREATED) public Antrag einreichen(@RequestBody @Valid Eingabe e) { return service.einreichen(e); }
    @PutMapping("/antraege/{id}/entscheidung") public Antrag entscheiden(@PathVariable @Positive long id,@RequestBody @Valid Entscheidung e) { return service.entscheiden(id,e); }
    @GetMapping("/dokumente") public PersonaldokumentService.Seite dokumente(@RequestParam(defaultValue="0") @Min(0) int seite) { return service.dokumente(seite); }
    @GetMapping("/dokumente/{id}/inhalt") public ResponseEntity<byte[]> dokument(@PathVariable @Positive long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"abrechnung-"+id+".pdf\"")
            .header("Content-Security-Policy","sandbox; default-src 'none'").body(service.dokument(id));
    }
}
