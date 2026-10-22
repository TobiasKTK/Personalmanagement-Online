package de.pemonline.sicherheit;

import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** Versand ohne Protokollierung von Empfängern, Passwörtern oder Links. */
@Component
public class KontenMail {
    private final JavaMailSender sender;
    private final boolean aktiv;
    private final String absender;
    private final String basisUrl;

    public KontenMail(ObjectProvider<JavaMailSender> sender,
            @Value("${pem.konto-mail.aktiv:false}") boolean aktiv,
            @Value("${pem.konto-mail.absender:}") String absender,
            @Value("${pem.konto-mail.basis-url:}") String basisUrl) {
        this.sender = sender.getIfAvailable(); this.aktiv = aktiv;
        this.absender = absender; this.basisUrl = basisUrl.replaceAll("/+$", "");
        if (aktiv) {
            if (this.sender == null || (this.sender instanceof JavaMailSenderImpl s && (s.getHost() == null || s.getHost().isBlank())))
                throw new IllegalStateException("Für Konto-E-Mails einen SMTP-Host konfigurieren.");
            EmailWiederherstellung.emailNormalisieren(absender);
            URI uri = URI.create(this.basisUrl);
            boolean lokal = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !("https".equals(uri.getScheme()) || (lokal && "http".equals(uri.getScheme()))))
                throw new IllegalStateException("Für Konto-E-Mails eine feste HTTPS-Basis-URL ohne Pfad konfigurieren; HTTP ist nur lokal erlaubt.");
        }
    }
    public boolean aktiv() { return aktiv; }
    public void link(String adresse, String zweck, String token) {
        boolean email = zweck.equals("EMAIL");
        String pfad = email ? "/email-bestaetigen" : "/passwort-zuruecksetzen";
        senden(adresse, email ? "E-Mail-Adresse bestätigen" : "Passwort zurücksetzen",
            (email ? "Bestätigen Sie Ihre E-Mail-Adresse für PEM Online. Der Link gilt 24 Stunden."
                : "Setzen Sie Ihr Passwort für PEM Online zurück. Der Link gilt 30 Minuten für alle Ihre Firmenzugänge.")
            + "\n\n" + basisUrl + pfad + "?token=" + token
            + "\n\nFalls Sie dies nicht angefordert haben, ignorieren Sie diese Nachricht. Geben Sie den Link nicht weiter.");
    }
    public void hinweis(String adresse, String text) { senden(adresse, "Änderung an Ihrem PEM-Online-Konto", text); }
    private void senden(String adresse, String betreff, String text) {
        if (!aktiv) throw new IllegalStateException("Konto-E-Mails sind nicht eingerichtet.");
        var mail = new SimpleMailMessage();
        mail.setFrom(absender); mail.setTo(adresse); mail.setSubject(betreff); mail.setText(text);
        sender.send(mail);
    }
}
