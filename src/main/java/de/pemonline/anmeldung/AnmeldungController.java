package de.pemonline.anmeldung;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "pem.anmeldung", havingValue = "lokal")
public class AnmeldungController {
    private final String vorlage;

    public AnmeldungController() throws IOException {
        vorlage = new ClassPathResource("anmeldung/login.html").getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> login(CsrfToken csrf, HttpServletRequest request) {
        String meldung = "";
        String kontoname = "";
        var sitzung = request.getSession(false);
        if (sitzung != null) {
            if (request.getParameterMap().containsKey("error")
                    && sitzung.getAttribute(AnmeldungFehlerHandler.KONTONAME) instanceof String name) {
                kontoname = name;
            }
            sitzung.removeAttribute(AnmeldungFehlerHandler.KONTONAME);
        }
        if (request.getParameterMap().containsKey("error")) {
            meldung = "Anmeldung fehlgeschlagen. Bitte prüfen Sie E-Mail oder Kontoname und Passwort.";
        } else if (request.getParameterMap().containsKey("expired")) {
            meldung = "Die Anmeldeseite ist abgelaufen. Bitte versuchen Sie es erneut.";
        }
        // Nur diese festen Platzhalter werden ersetzt; alle eingesetzten Werte werden HTML-escaped.
        // Der maskierte Request-Token erlaubt auch eine Anmeldung ohne JavaScript.
        String html = vorlage.replace("{{csrfName}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
                .replace("{{csrfToken}}", HtmlUtils.htmlEscape(csrf.getToken()))
                .replace("{{meldung}}", HtmlUtils.htmlEscape(meldung))
                .replace("{{meldungVersteckt}}", meldung.isEmpty() ? "hidden" : "")
                .replace("{{kontoname}}", HtmlUtils.htmlEscape(kontoname));
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
                .body(html);
    }
}
