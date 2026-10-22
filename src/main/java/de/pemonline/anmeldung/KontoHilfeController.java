package de.pemonline.anmeldung;

import de.pemonline.sicherheit.EmailWiederherstellung;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

/** Serverseitige Formulare funktionieren auch ohne JavaScript und ohne aktive Firma. */
@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "pem.anmeldung", havingValue = "lokal")
public class KontoHilfeController {
    private final EmailWiederherstellung konten;
    public KontoHilfeController(EmailWiederherstellung konten) { this.konten = konten; }

    @GetMapping("/konto/email")
    public ResponseEntity<String> email(CsrfToken csrf) { return emailSeite(csrf, "", false); }
    @PostMapping("/konto/email")
    public ResponseEntity<String> emailSpeichern(CsrfToken csrf, @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String passwort) {
        try {
            konten.emailAnfordern(email, passwort);
            return emailSeite(csrf, "Die Bestätigungs-E-Mail wurde versendet. Bitte öffnen Sie den Link und bestätigen Sie Ihre Adresse. Prüfen Sie auch Ihren Spamordner.", false);
        } catch (IllegalArgumentException e) { return emailSeite(csrf, e.getMessage(), true); }
    }
    private ResponseEntity<String> emailSeite(CsrfToken csrf, String meldung, boolean fehler) {
        StringBuilder inhalt = new StringBuilder("<p>Diese E-Mail-Adresse gilt für Ihr Konto in allen Firmen. Sie können sich damit anmelden und Ihr Passwort zurücksetzen, sobald Sie die Adresse bestätigt haben.</p>");
        var adressen = konten.adressen();
        if (adressen.isEmpty()) inhalt.append("<p>Noch keine E-Mail-Adresse hinterlegt.</p>");
        for (var adresse : adressen) inhalt.append("<p><strong>").append(adresse.bestaetigt() ? "Bestätigt: " : "Bestätigung ausstehend: ")
            .append("</strong>").append(h(adresse.adresse())).append("</p>");
        if (konten.mailAktiv()) {
            inhalt.append("<p>Bei einem Wechsel bleibt die bisher bestätigte Adresse gültig, bis die neue bestätigt ist. Zum erneuten Senden geben Sie dieselbe Adresse ein.</p>")
                .append(formular("/konto/email", csrf))
                .append(feld("email", "E-Mail-Adresse", "email", "email", 254))
                .append(feld("passwort", "Aktuelles Passwort", "password", "current-password", 128))
                .append(button("Bestätigungslink senden"));
        } else inhalt.append("<p>").append(h(EmailWiederherstellung.NICHT_EINGERICHTET)).append("</p>");
        inhalt.append("<p><a href=\"/\">Zurück zur Anwendung</a></p>");
        return seite("Meine E-Mail", inhalt.toString(), meldung, fehler);
    }
    @GetMapping("/passwort-vergessen")
    public ResponseEntity<String> vergessen(CsrfToken csrf) { return vergessenSeite(csrf, ""); }
    @PostMapping("/passwort-vergessen")
    public ResponseEntity<String> anfordern(CsrfToken csrf, HttpServletRequest request,
            @RequestParam(defaultValue = "") String kennung) {
        konten.passwortAnfordern(kennung, request.getRemoteAddr());
        return vergessenSeite(csrf, konten.mailAktiv() ? EmailWiederherstellung.NEUTRAL : EmailWiederherstellung.NICHT_EINGERICHTET);
    }
    private ResponseEntity<String> vergessenSeite(CsrfToken csrf, String meldung) {
        String inhalt = konten.mailAktiv()
            ? "<p>Geben Sie Ihre bestätigte E-Mail-Adresse oder Ihren Kontonamen ein. Sie erhalten einen Link, mit dem Sie ein neues Passwort für alle Ihre Firmenzugänge festlegen können.</p>"
                + formular("/passwort-vergessen", csrf) + feld("kennung", "E-Mail oder Kontoname", "text", "username", 254) + button("Rücksetzlink senden")
                + "<p>Keine bestätigte E-Mail-Adresse hinterlegt oder kein Zugriff auf das Postfach? Bitte wenden Sie sich an Ihre Administration.</p>"
            : "<p>" + h(EmailWiederherstellung.NICHT_EINGERICHTET) + "</p>";
        return seite("Passwort vergessen?", inhalt + loginLink(), meldung, false);
    }
    @GetMapping("/email-bestaetigen")
    public ResponseEntity<String> bestaetigen(CsrfToken csrf, @RequestParam(defaultValue = "") String token) {
        return seite("E-Mail bestätigen", "<p>Bestätigen Sie die E-Mail-Adresse für Ihr PEM-Online-Konto. Falls Sie diese Änderung nicht angefordert haben, schließen Sie diese Seite.</p>"
            + formular("/email-bestaetigen", csrf) + tokenFeld(token) + button("E-Mail bestätigen") + loginLink(), "", false);
    }
    @PostMapping("/email-bestaetigen")
    public ResponseEntity<String> bestaetigung(HttpServletRequest request, @RequestParam(defaultValue = "") String token) {
        try {
            konten.linkVersuch(request.getRemoteAddr());
            konten.emailBestaetigen(token);
            return seite("E-Mail bestätigt", "<p>Sie können sich jetzt mit Ihrer E-Mail-Adresse oder Ihrem bisherigen Kontonamen anmelden.</p>" + loginLink(), "Ihre E-Mail-Adresse wurde bestätigt.", false);
        } catch (IllegalArgumentException e) {
            return seite("E-Mail bestätigen", "<p><a href=\"/konto/email\">Neuen Bestätigungslink anfordern</a></p>" + loginLink(), e.getMessage(), true);
        }
    }
    @GetMapping("/passwort-zuruecksetzen")
    public ResponseEntity<String> zuruecksetzen(CsrfToken csrf, @RequestParam(defaultValue = "") String token) {
        return passwortSeite(csrf, token, "");
    }
    @PostMapping("/passwort-zuruecksetzen")
    public ResponseEntity<String> neuesPasswort(CsrfToken csrf, HttpServletRequest request, @RequestParam(defaultValue = "") String token,
            @RequestParam(defaultValue = "") String passwort, @RequestParam(defaultValue = "") String wiederholung) {
        try {
            konten.linkVersuch(request.getRemoteAddr());
            konten.passwortZuruecksetzen(token, passwort, wiederholung);
            return seite("Passwort geändert", "<p>Ihr neues Passwort gilt für alle Ihre Firmenzugänge. Bitte melden Sie sich erneut an.</p>" + loginLink(), "Ihr Passwort wurde geändert. Bestehende Sitzungen wurden beendet.", false);
        } catch (IllegalArgumentException e) { return passwortSeite(csrf, token, e.getMessage()); }
    }
    private ResponseEntity<String> passwortSeite(CsrfToken csrf, String token, String meldung) {
        return seite("Neues Passwort festlegen", "<p>Verwenden Sie 12–128 Zeichen. Die Änderung gilt für alle Ihre Firmenzugänge.</p>"
            + formular("/passwort-zuruecksetzen", csrf) + tokenFeld(token)
            + feld("passwort", "Neues Passwort", "password", "new-password", 128)
            + feld("wiederholung", "Neues Passwort wiederholen", "password", "new-password", 128)
            + button("Passwort speichern") + "<p><a href=\"/passwort-vergessen\">Neuen Rücksetzlink anfordern</a></p>" + loginLink(), meldung, !meldung.isEmpty());
    }
    private static String formular(String action, CsrfToken csrf) {
        return "<form method=\"post\" action=\"" + action + "\"><input type=\"hidden\" name=\"" + h(csrf.getParameterName()) + "\" value=\"" + h(csrf.getToken()) + "\">";
    }
    private static String tokenFeld(String token) {
        return "<input type=\"hidden\" name=\"token\" value=\"" + h(token.length() <= 128 ? token : "") + "\">";
    }
    private static String feld(String name, String label, String typ, String autocomplete, int max) {
        return "<div class=\"login-field\"><label for=\"" + name + "\">" + label + "</label><input id=\"" + name
            + "\" name=\"" + name + "\" type=\"" + typ + "\" autocomplete=\"" + autocomplete + "\" required maxlength=\"" + max + "\"></div>";
    }
    private static String button(String text) { return "<div class=\"login-actions\"><button class=\"konto-button\" type=\"submit\">" + text + "</button></div></form>"; }
    private static String loginLink() { return "<p><a href=\"/login\">Zur Anmeldung</a></p>"; }
    private static String h(String text) { return HtmlUtils.htmlEscape(text); }
    private static ResponseEntity<String> seite(String titel, String inhalt, String meldung, boolean fehler) {
        String html = "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>" + h(titel) + " · Personalmanagement Online</title><link rel=\"icon\" href=\"data:,\"><link rel=\"stylesheet\" href=\"/anmeldung/login.css\"></head>"
            + "<body><main class=\"login-layout\"><div class=\"login-shell\"><section class=\"login-card konto-hilfe\"><p class=\"login-produkt\">Personalmanagement Online</p><h1>" + h(titel) + "</h1>"
            + (meldung.isEmpty() ? "" : "<p class=\"login-message " + (fehler ? "" : "erfolg") + "\" role=\"" + (fehler ? "alert" : "status") + "\">" + h(meldung) + "</p>")
            + inhalt + "</section></div></main></body></html>";
        return ResponseEntity.status(fehler ? 400 : 200).contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
            .body(html);
    }
}
