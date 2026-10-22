package de.pemonline.api;

import java.util.List;
import java.util.Objects;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import de.pemonline.sicherheit.KontenService;
import de.pemonline.sicherheit.KontenService.*;
import de.pemonline.mandant.*;

@RestController
@RequestMapping("/api")
public class KontenController {
    public record Sitzung(String name, String rolle, Long mandantenspeicherId, String firma, boolean systemverwaltung, boolean keycloak) {}
    private final KontenService konten;
    private final Anmeldekonten anmeldekonten;
    public KontenController(KontenService konten, Anmeldekonten anmeldekonten) {
        this.konten = konten;
        this.anmeldekonten = anmeldekonten;
    }

    @GetMapping("/sitzung")
    public Sitzung sitzung(Authentication anmeldung) {
        var benutzer = (MandantenBenutzer) anmeldung.getPrincipal();
        String firmenname = aktuelleFirma(anmeldung.getName(), benutzer.mandantenspeicherId());
        return new Sitzung(anmeldung.getName(), benutzer.rolle(), benutzer.mandantenspeicherId(),
                firmenname, benutzer.systemverwaltung(), benutzer instanceof de.pemonline.sicherheit.KeycloakBenutzer);
    }

    private String aktuelleFirma(String kontoname, Long mandantenspeicherId) {
        for (var zugang : anmeldekonten.zugaenge(kontoname)) {
            if (Objects.equals(zugang.mandantenspeicherId(), mandantenspeicherId)) {
                return zugang.name();
            }
        }
        // Systemverwaltungskonten können angemeldet sein, ohne einer Firma zugeordnet zu sein.
        return "";
    }

    @GetMapping("/konten")
    public List<Konto> liste() {
        return konten.liste();
    }

    @PostMapping("/konten")
    @ResponseStatus(HttpStatus.CREATED)
    public void anlegen(@Valid @RequestBody Eingabe eingabe, Authentication anmeldung) {
        konten.speichern(eingabe, true);
    }

    @PutMapping("/konten")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void aendern(@Valid @RequestBody Eingabe eingabe, Authentication anmeldung) {
        konten.speichern(eingabe, false);
    }
}
