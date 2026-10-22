package de.pemonline.api;

import de.pemonline.abwesenheit.*;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/mitarbeiter/{person}")
public class MitarbeiterDetailsController {
    private final AbwesenheitService abwesenheiten;
    private final VerlaufService verlauf;
    public MitarbeiterDetailsController(AbwesenheitService abwesenheiten, VerlaufService verlauf) { this.abwesenheiten = abwesenheiten; this.verlauf = verlauf; }
    @GetMapping("/abwesenheiten") public AbwesenheitSeite liste(@PathVariable @Positive long person,
            @RequestParam(defaultValue = "0") @Min(0) int seite, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int groesse) {
        return abwesenheiten.liste(person, seite, groesse);
    }
    @PostMapping("/abwesenheiten") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Abwesenheit anlegen(@PathVariable @Positive long person, @Valid @RequestBody AbwesenheitEingabe e) { return abwesenheiten.speichern(person, null, e); }
    @PutMapping("/abwesenheiten/{id}") public Abwesenheit aendern(@PathVariable @Positive long person, @PathVariable @Positive long id, @Valid @RequestBody AbwesenheitEingabe e) {
        return abwesenheiten.speichern(person, id, e);
    }
    @DeleteMapping("/abwesenheiten/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void loeschen(@PathVariable @Positive long person, @PathVariable @Positive long id, @RequestParam @PositiveOrZero long version) {
        abwesenheiten.loeschen(person, id, version);
    }
    @GetMapping("/verlauf") public VerlaufService.Seite verlauf(@PathVariable @Positive long person,
            @RequestParam(defaultValue = "0") @Min(0) int seite, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int groesse) {
        return verlauf.liste(person, seite, groesse);
    }
}
