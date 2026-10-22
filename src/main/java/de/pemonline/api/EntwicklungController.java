package de.pemonline.api;

import de.pemonline.entwicklung.EntwicklungService;
import static de.pemonline.entwicklung.EntwicklungModel.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/entwicklung")
public class EntwicklungController {
    private final EntwicklungService service;
    public EntwicklungController(EntwicklungService service) { this.service = service; }
    @GetMapping("/personen") public List<Person> personen() { return service.personen(); }
    @GetMapping("/kenntnisse") public List<Kenntnis> kenntnisse() { return service.kenntnisse(); }
    @PostMapping("/kenntnisse") @ResponseStatus(HttpStatus.CREATED) public Kenntnis kenntnis(@Valid @RequestBody KenntnisEingabe e) { return service.kenntnisSpeichern(null, e); }
    @PutMapping("/kenntnisse/{id}") public Kenntnis kenntnis(@PathVariable @Positive long id, @Valid @RequestBody KenntnisEingabe e) { return service.kenntnisSpeichern(id, e); }
    @GetMapping("/profile") public List<Profil> profile() { return service.profile(); }
    @PostMapping("/profile") @ResponseStatus(HttpStatus.CREATED) public Profil profil(@Valid @RequestBody ProfilEingabe e) { return service.profilSpeichern(null, e); }
    @PutMapping("/profile/{id}") public Profil profil(@PathVariable @Positive long id, @Valid @RequestBody ProfilEingabe e) { return service.profilSpeichern(id, e); }
    @GetMapping("/planstellen") public List<Planstelle> planstellen() { return service.planstellen(); }
    @PostMapping("/planstellen") @ResponseStatus(HttpStatus.CREATED) public Planstelle planstelle(@Valid @RequestBody PlanstellenEingabe e) { return service.planstelleSpeichern(null, e); }
    @PutMapping("/planstellen/{id}") public Planstelle planstelle(@PathVariable @Positive long id, @Valid @RequestBody PlanstellenEingabe e) { return service.planstelleSpeichern(id, e); }
    @GetMapping("/nachweise") public List<Nachweis> nachweise(@RequestParam @Positive long mitarbeiterId) { return service.nachweise(mitarbeiterId); }
    @PostMapping("/nachweise") @ResponseStatus(HttpStatus.CREATED) public Nachweis nachweis(@Valid @RequestBody NachweisEingabe e) { return service.nachweisSpeichern(null, e); }
    @PutMapping("/nachweise/{id}") public Nachweis nachweis(@PathVariable @Positive long id, @Valid @RequestBody NachweisEingabe e) { return service.nachweisSpeichern(id, e); }
    @GetMapping("/abgleich") public List<Abgleich> abgleich(@RequestParam @Positive long mitarbeiterId, @RequestParam(required=false) @Positive Long profilId, @RequestParam LocalDate stichtag) { return service.abgleich(mitarbeiterId, profilId, stichtag); }
    @GetMapping("/ausbildungen") public List<Ausbildung> ausbildungen() { return service.ausbildungen(); }
    @PostMapping("/ausbildungen") @ResponseStatus(HttpStatus.CREATED) public Ausbildung ausbildung(@Valid @RequestBody AusbildungEingabe e) { return service.ausbildungSpeichern(null, e); }
    @PutMapping("/ausbildungen/{id}") public Ausbildung ausbildung(@PathVariable @Positive long id, @Valid @RequestBody AusbildungEingabe e) { return service.ausbildungSpeichern(id, e); }
    @GetMapping("/stationen") public List<Station> stationen(@RequestParam @Positive long ausbildungId) { return service.stationen(ausbildungId); }
    @PostMapping("/stationen") @ResponseStatus(HttpStatus.CREATED) public Station station(@Valid @RequestBody StationEingabe e) { return service.stationSpeichern(null, e); }
    @PutMapping("/stationen/{id}") public Station station(@PathVariable @Positive long id, @Valid @RequestBody StationEingabe e) { return service.stationSpeichern(id, e); }
    @DeleteMapping("/{bereich}/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void loeschen(@PathVariable String bereich, @PathVariable @Positive long id, @RequestParam @PositiveOrZero long version) {
        EntwicklungService.Bereich ziel;
        try { ziel = EntwicklungService.Bereich.valueOf(bereich.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Bereich nicht gefunden."); }
        service.loeschen(ziel, id, version);
    }
}
