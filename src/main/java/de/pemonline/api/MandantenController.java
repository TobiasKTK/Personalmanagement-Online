package de.pemonline.api;

import de.pemonline.mandant.*;
import de.pemonline.fachlich.FachlicherFehler;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import de.pemonline.sicherheit.KeycloakBenutzer;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class MandantenController {
    public record Wechsel(@Positive long mandantenspeicherId, Long bisherigeMandantenspeicherId) {}
    private final Anmeldekonten konten;
    private final MandantenService mandanten;
    public MandantenController(Anmeldekonten konten, MandantenService mandanten) { this.konten = konten; this.mandanten = mandanten; }
    @GetMapping("/mandanten") public List<Anmeldekonten.Zugang> meine(Authentication auth) { return konten.zugaenge(auth.getName()); }
    @PostMapping("/mandanten/wechsel") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void wechseln(@RequestBody @Valid Wechsel e, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        var bisher = (MandantenBenutzer) auth.getPrincipal();
        if (!Objects.equals(e.bisherigeMandantenspeicherId(), bisher.mandantenspeicherId()))
            throw new FachlicherFehler(FachlicherFehler.Art.KONFLIKT, "Mandant wurde bereits in einem anderen Fenster gewechselt. Bitte neu laden.");
        var benutzer = new MandantenBenutzer(konten.fuerMandant(auth.getName(), e.mandantenspeicherId()), bisher);
        if (bisher instanceof KeycloakBenutzer oidc) benutzer = new KeycloakBenutzer(benutzer, oidc);
        benutzer.eraseCredentials();
        var kontext = SecurityContextHolder.createEmptyContext();
        kontext.setAuthentication(auth instanceof OAuth2AuthenticationToken oauth
                ? new OAuth2AuthenticationToken((KeycloakBenutzer) benutzer, benutzer.getAuthorities(), oauth.getAuthorizedClientRegistrationId())
                : UsernamePasswordAuthenticationToken.authenticated(benutzer, null, benutzer.getAuthorities()));
        SecurityContextHolder.setContext(kontext);
        new HttpSessionSecurityContextRepository().saveContext(kontext, request, response);
    }
    @GetMapping("/mandantenverwaltung") public List<MandantenService.Speicher> alle() { return mandanten.liste(); }
    @PostMapping("/mandantenverwaltung") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public MandantenService.Speicher anlegen(@Valid @RequestBody MandantenService.Eingabe e) { return mandanten.anlegen(e); }
    @PostMapping("/mandantenverwaltung/{id}/einrichten")
    public MandantenService.Speicher einrichten(@PathVariable long id) { return mandanten.einrichten(id); }
    @PutMapping("/mandantenverwaltung/{id}") public MandantenService.Speicher umbenennen(@PathVariable long id, @Valid @RequestBody MandantenService.Eingabe e) { return mandanten.umbenennen(id, e); }
    @PutMapping("/mandantenverwaltung/{id}/konten") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void zuordnen(@PathVariable long id, @Valid @RequestBody MandantenService.Zuordnung e) { mandanten.zuweisung(id, e); }
    @GetMapping("/mandantenverwaltung/konten") public List<MandantenService.GlobalesKonto> konten() { return mandanten.konten(); }
    @PutMapping("/mandantenverwaltung/konten/{name}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void kontoAendern(@PathVariable String name, @Valid @RequestBody MandantenService.KontoEingabe e) { mandanten.kontoAendern(name, e); }
}
