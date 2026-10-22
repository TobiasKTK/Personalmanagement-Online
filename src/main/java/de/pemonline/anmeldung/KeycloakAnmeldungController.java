package de.pemonline.anmeldung;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "pem.anmeldung", havingValue = "keycloak", matchIfMissing = true)
public class KeycloakAnmeldungController {
    private final ClientRegistrationRepository clients;
    public KeycloakAnmeldungController(ClientRegistrationRepository clients) { this.clients = clients; }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> login(HttpServletRequest request) {
        String meldung = request.getParameterMap().containsKey("error")
                ? "<p role=\"alert\">Die Anmeldung konnte nicht abgeschlossen werden. Bitte erneut versuchen oder die Administration um Freischaltung Ihres pemonline-Zugangs bitten.</p>"
                : request.getParameterMap().containsKey("abgemeldet") ? "<p role=\"status\">Sie wurden abgemeldet.</p>" : "";
        String html = """
                <!doctype html><html lang="de"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Anmelden · Personalmanagement Online</title>
                <link rel="stylesheet" href="/anmeldung/keycloak.css"></head>
                <body><main><span class="marke">PEM Online</span><h1>Personalmanagement Online</h1>
                <p>Melden Sie sich mit Ihrem zentralen Benutzerkonto an.</p>
                %s
                <a class="anmelden" href="/oauth2/authorization/keycloak">Mit Keycloak anmelden</a>
                <p class="hilfe">Passwort und Kontodaten verwalten Sie bei Keycloak. Für einen Firmenzugang wenden Sie sich an Ihre Administration.</p>
                </main></body></html>
                """.formatted(meldung);
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'")
                .body(html);
    }

    @GetMapping("/konto/email")
    public String konto() {
        return "redirect:" + clients.findByRegistrationId("keycloak").getProviderDetails().getIssuerUri() + "/account";
    }

    @GetMapping({"/passwort-vergessen", "/passwort-zuruecksetzen", "/email-bestaetigen"})
    public String alteKontohilfe() { return "redirect:/login"; }
}
