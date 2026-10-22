package de.pemonline.api;

import de.pemonline.personalprozesse.PersonalprozessService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static de.pemonline.personalprozesse.PersonalprozessModel.*;

@RestController
@RequestMapping("/api/personalprozesse")
public class PersonalprozessController {
    private final PersonalprozessService service;
    public PersonalprozessController(PersonalprozessService service) { this.service = service; }
    @GetMapping("/vorlagen") public List<Vorlage> vorlagen() { return service.vorlagen(); }
    @GetMapping("/konten") public List<String> konten() { return service.konten(); }
    @PostMapping("/vorlagen") @ResponseStatus(HttpStatus.CREATED)
    public Vorlage anlegen(@RequestBody @Valid VorlagenEingabe e) { return service.anlegen(e); }
    @PutMapping("/vorlagen/{id}") public Vorlage speichern(@PathVariable long id, @RequestBody @Valid VorlagenEingabe e) { return service.vorlageSpeichern(id, e); }
    @PostMapping("/vorgaenge") @ResponseStatus(HttpStatus.CREATED)
    public Vorgang starten(@RequestBody @Valid Start e) { return service.starten(e); }
    @GetMapping("/aufgaben") public Seite aufgaben(@RequestParam(defaultValue = "ALLE") String status, @RequestParam(defaultValue = "0") int seite) { return service.aufgaben(status, seite); }
    @GetMapping("/aufgaben/{id}") public Aufgabe aufgabe(@PathVariable long id) { return service.aufgabe(id); }
    @PutMapping("/aufgaben/{id}/status") public Aufgabe status(@PathVariable long id, @RequestBody @Valid Status e) { return service.statusSpeichern(id, e); }
    @PutMapping("/aufgaben/{id}/zuordnung") public Aufgabe zuordnen(@PathVariable long id, @RequestBody @Valid Zuordnung e) { return service.zuordnen(id, e); }
}
