package de.pemonline.api;

import java.net.URI;
import de.pemonline.mitarbeiter.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
public class MitarbeiterController {
    private final MitarbeiterService service;

    public MitarbeiterController(MitarbeiterService service) {
        this.service = service;
    }

    @GetMapping("/mitarbeiter")
    public MitarbeiterSeite liste(
            @RequestParam(defaultValue = "") @Size(max = 120) String suche,
            @RequestParam(defaultValue = "0") @Min(0) int seite,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int groesse,
            @RequestParam(required = false) Long organisationseinheitId,
            @RequestParam(required = false) String beschaeftigung,
            @RequestParam(required = false) java.time.LocalDate stichtag,
            @RequestParam(required = false) String sortierung, @RequestParam(required = false) String richtung) {
        return service.suchen(new MitarbeiterFilter(suche, organisationseinheitId, beschaeftigung, stichtag, sortierung, richtung), seite, groesse);
    }

    public record PersonalnummerVorschlag(long personalnummer) {}

    @GetMapping("/mitarbeiter/naechste-personalnummer")
    public PersonalnummerVorschlag naechstePersonalnummer() {
        return new PersonalnummerVorschlag(service.naechstePersonalnummer());
    }

    @GetMapping("/mitarbeiter/{id}")
    public Mitarbeiter einzelner(@PathVariable @Positive long id) {
        return service.finden(id);
    }

    @PostMapping("/mitarbeiter")
    public ResponseEntity<Mitarbeiter> anlegen(@Valid @RequestBody MitarbeiterEingabe eingabe) {
        Mitarbeiter mitarbeiter = service.anlegen(eingabe);
        return ResponseEntity.created(URI.create("/api/mitarbeiter/" + mitarbeiter.id())).body(mitarbeiter);
    }

    @PutMapping("/mitarbeiter/{id}")
    public Mitarbeiter aendern(@PathVariable @Positive long id,
                               @Valid @RequestBody MitarbeiterEingabe eingabe) {
        return service.aendern(id, eingabe);
    }

    @DeleteMapping("/mitarbeiter/{id}")
    public ResponseEntity<Void> loeschen(@PathVariable @Positive long id,
                                        @RequestParam @PositiveOrZero long version) {
        service.loeschen(id, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/statistik")
    public Statistik statistik(@RequestParam(defaultValue = "") String suche,
            @RequestParam(required = false) Long organisationseinheitId,
            @RequestParam(required = false) String beschaeftigung, @RequestParam(required = false) java.time.LocalDate stichtag) {
        return service.statistik(new MitarbeiterFilter(suche, organisationseinheitId, beschaeftigung, stichtag, null, null));
    }
}
