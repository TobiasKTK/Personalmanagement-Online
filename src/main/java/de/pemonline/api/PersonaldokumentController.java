package de.pemonline.api;

import de.pemonline.personaldaten.PersonaldokumentService;
import static de.pemonline.personaldaten.PersonaldokumentService.*;
import jakarta.validation.constraints.*;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequestMapping("/api/mitarbeiter/{person}/personaldaten/dokumente")
public class PersonaldokumentController {
    private final PersonaldokumentService service;
    public PersonaldokumentController(PersonaldokumentService service) { this.service = service; }
    @GetMapping public Seite liste(@PathVariable @Positive long person, @RequestParam(defaultValue="0") @Min(0) int seite) { return service.liste(person, seite); }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Dokument importieren(@PathVariable @Positive long person, @RequestParam String titel, @RequestParam Art art,
            @RequestParam Quelle quelle, @RequestParam(defaultValue="") String monat, @RequestParam MultipartFile datei) throws IOException {
        if (datei.getSize() > 5242880) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF-Dateien dürfen höchstens 5 MiB groß sein.");
        return service.importieren(person, new Metadaten(titel, art, quelle, monat), datei.getBytes());
    }
    @GetMapping("/{id}/inhalt") public ResponseEntity<byte[]> inhalt(@PathVariable @Positive long person, @PathVariable @Positive long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"dokument-" + id + ".pdf\"")
            .header("Content-Security-Policy", "sandbox; default-src 'none'").body(service.inhalt(person, id));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void loeschen(@PathVariable @Positive long person, @PathVariable @Positive long id) { service.loeschen(person, id); }
}
