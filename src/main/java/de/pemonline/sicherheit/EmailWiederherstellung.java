package de.pemonline.sicherheit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailWiederherstellung {
    public static final String NEUTRAL = "Wenn ein aktives Konto mit bestätigter E-Mail-Adresse gefunden wurde, erhalten Sie einen Link zum Zurücksetzen. Bitte prüfen Sie auch Ihren Spamordner.";
    public static final String NICHT_EINGERICHTET = "Die Wiederherstellung per E-Mail ist noch nicht eingerichtet. Bitte wenden Sie sich an Ihre Administration.";
    public record Adresse(String adresse, boolean bestaetigt) {}
    private record Konto(String username, String password, boolean enabled) {}
    private record Token(String username, String adresse, String passwortStand) {}
    private record Versand(String adresse, String zweck, String token) {
        @Override public String toString() { return "Versand[geschützt]"; }
    }
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final KontenMail mail;
    private final SecureRandom zufall = new SecureRandom();
    // Begrenzte Warteschlange: öffentliche Antworten warten weder auf Kontosuche noch SMTP.
    private final ThreadPoolExecutor versand = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100), Thread.ofPlatform().daemon().name("konto-mail-", 0).factory());

    public EmailWiederherstellung(@Qualifier("verwaltungJdbc") JdbcClient jdbc,
            @Qualifier("basisDataSource") DataSource basis, Clock clock, PasswordEncoder encoder,
            SessionRegistry sessions, KontenMail mail) {
        this.jdbc = jdbc; this.clock = clock; this.encoder = encoder; this.sessions = sessions; this.mail = mail;
        tx = new TransactionTemplate(new DataSourceTransactionManager(basis));
    }
    @PreDestroy void beenden() { versand.shutdownNow(); }
    public boolean mailAktiv() { return mail.aktiv(); }
    public void linkVersuch(String ip) {
        if (!limit("link-ip:" + ip, 30, Duration.ofMinutes(15)))
            throw new IllegalArgumentException("Zu viele Versuche. Bitte versuchen Sie es in 15 Minuten erneut.");
    }

    @PreAuthorize("isAuthenticated()")
    public List<Adresse> adressen() {
        return jdbc.sql("SELECT adresse, bestaetigt FROM VERWALTUNG.konto_email WHERE username = :name AND (bestaetigt = TRUE OR angelegt > :ab) ORDER BY bestaetigt DESC")
            .param("name", eigenerName()).param("ab", clock.millis() - Duration.ofDays(1).toMillis()).query(Adresse.class).list();
    }
    @PreAuthorize("isAuthenticated()")
    public void emailAnfordern(String adresse, String passwort) {
        if (!mail.aktiv()) throw new IllegalArgumentException(NICHT_EINGERICHTET);
        String name = eigenerName();
        if (!limit("email:" + name, 5, Duration.ofHours(1))) throw new IllegalArgumentException("Zu viele Anfragen. Bitte versuchen Sie es in einer Stunde erneut.");
        String normalisiert = emailNormalisieren(adresse);
        Versand nachricht = tx.execute(status -> {
            sperren(); bereinigen();
            Konto konto = konto(name);
            if (!konto.enabled() || passwort == null || passwort.length() > 128 || !encoder.matches(passwort, konto.password()))
                throw new IllegalArgumentException("Bitte prüfen Sie Ihr aktuelles Passwort.");
            var belegt = jdbc.sql("SELECT username FROM VERWALTUNG.konto_email WHERE adresse = :adresse")
                .param("adresse", normalisiert).query(String.class).optional();
            if (belegt.isPresent() && !belegt.get().equals(name))
                throw new IllegalArgumentException("Diese E-Mail-Adresse kann nicht verwendet werden.");
            if (jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_email WHERE username = :name AND adresse = :adresse AND bestaetigt = TRUE")
                    .param("name", name).param("adresse", normalisiert).query(Long.class).single() > 0)
                throw new IllegalArgumentException("Diese E-Mail-Adresse ist bereits bestätigt.");
            jdbc.sql("DELETE FROM VERWALTUNG.konto_email WHERE username = :name AND bestaetigt = FALSE").param("name", name).update();
            jdbc.sql("INSERT INTO VERWALTUNG.konto_email (adresse, username, bestaetigt, angelegt) VALUES (:adresse, :name, FALSE, :jetzt)")
                .param("adresse", normalisiert).param("name", name).param("jetzt", clock.millis()).update();
            return tokenAnlegen(konto, normalisiert, "EMAIL", Duration.ofDays(1));
        });
        try { mail.link(nachricht.adresse(), nachricht.zweck(), nachricht.token()); }
        catch (RuntimeException e) {
            versandfehler();
            throw new IllegalArgumentException("Die Bestätigungs-E-Mail konnte nicht versendet werden. Bitte versuchen Sie es später erneut oder wenden Sie sich an Ihre Administration.");
        }
    }
    public void emailBestaetigen(String token) {
        String alteAdresse = tx.execute(status -> {
            sperren();
            Token t = tokenPruefen(token, "EMAIL");
            if (jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_email WHERE username = :name AND adresse = :adresse AND bestaetigt = FALSE")
                    .param("name", t.username()).param("adresse", t.adresse()).query(Long.class).single() != 1) throw ungueltigerLink();
            String alt = bestaetigteAdresse(t.username());
            jdbc.sql("DELETE FROM VERWALTUNG.konto_email WHERE username = :name AND bestaetigt = TRUE").param("name", t.username()).update();
            jdbc.sql("UPDATE VERWALTUNG.konto_email SET bestaetigt = TRUE WHERE username = :name AND adresse = :adresse")
                .param("name", t.username()).param("adresse", t.adresse()).update();
            jdbc.sql("DELETE FROM VERWALTUNG.konto_token WHERE username = :name").param("name", t.username()).update();
            return alt;
        });
        if (alteAdresse != null) hinweis(alteAdresse, "Die E-Mail-Adresse Ihres PEM-Online-Kontos wurde geändert. Falls Sie diese Änderung nicht veranlasst haben, wenden Sie sich bitte sofort an Ihre Administration.");
    }
    public void passwortAnfordern(String eingabe, String ip) {
        if (!mail.aktiv()) return;
        // Keine Proxy-Header übernehmen: nur die tatsächliche Gegenstelle begrenzen.
        if (!limit("reset-ip:" + ip, 10, Duration.ofMinutes(15))) return;
        String name = eingabe == null ? "" : eingabe.strip();
        if (name.length() > 254 || name.isEmpty()) return;
        if (name.contains("@")) {
            try { name = emailNormalisieren(name); } catch (IllegalArgumentException e) { return; }
        }
        final String suche = name;
        einreihen(() -> {
            if (!limit("reset-eingabe:" + suche, 3, Duration.ofHours(1))) return;
            Versand nachricht = tx.execute(status -> {
                sperren(); bereinigen();
                var konto = jdbc.sql("SELECT k.username, k.password, k.enabled FROM VERWALTUNG.konto k JOIN VERWALTUNG.konto_email e ON e.username = k.username AND e.bestaetigt = TRUE WHERE k.enabled = TRUE AND "
                        + (suche.contains("@") ? "e.adresse = :suche" : "k.username = :suche"))
                    .param("suche", suche).query(Konto.class).optional();
                if (konto.isEmpty() || !limitInTransaktion("reset-konto:" + konto.get().username(), 3, Duration.ofHours(1))) return null;
                return tokenAnlegen(konto.get(), bestaetigteAdresse(konto.get().username()), "PASSWORT", Duration.ofMinutes(30));
            });
            if (nachricht != null) mail.link(nachricht.adresse(), nachricht.zweck(), nachricht.token());
        });
    }
    public void passwortZuruecksetzen(String token, String passwort, String wiederholung) {
        if (passwort == null || passwort.length() < 12 || passwort.length() > 128)
            throw new IllegalArgumentException("Passwörter müssen 12–128 Zeichen enthalten.");
        if (!passwort.equals(wiederholung)) throw new IllegalArgumentException("Die Passwörter stimmen nicht überein.");
        Token t = tx.execute(status -> {
            sperren();
            Token gefunden = tokenPruefen(token, "PASSWORT");
            if (!gefunden.adresse().equals(bestaetigteAdresse(gefunden.username()))) throw ungueltigerLink();
            jdbc.sql("UPDATE VERWALTUNG.konto SET password = :passwort WHERE username = :name")
                .param("name", gefunden.username()).param("passwort", encoder.encode(passwort)).update();
            jdbc.sql("DELETE FROM VERWALTUNG.konto_token WHERE username = :name").param("name", gefunden.username()).update();
            jdbc.sql("DELETE FROM VERWALTUNG.konto_email WHERE username = :name AND bestaetigt = FALSE").param("name", gefunden.username()).update();
            return gefunden;
        });
        for (Object principal : sessions.getAllPrincipals())
            if (principal instanceof UserDetails u && u.getUsername().equals(t.username()))
                sessions.getAllSessions(principal, false).forEach(s -> s.expireNow());
        hinweis(t.adresse(), "Ihr Passwort für PEM Online wurde zurückgesetzt. Bestehende Sitzungen wurden beendet. Die Änderung gilt für alle Ihre Firmenzugänge. Falls Sie dies nicht veranlasst haben, wenden Sie sich bitte sofort an Ihre Administration.");
    }
    private Token tokenPruefen(String token, String zweck) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw ungueltigerLink();
        Token t = jdbc.sql("SELECT username, adresse, passwort_stand FROM VERWALTUNG.konto_token WHERE token_hash = :hash AND zweck = :zweck AND gueltig_bis > :jetzt")
            .param("hash", hash(token)).param("zweck", zweck).param("jetzt", clock.millis()).query(Token.class).optional().orElseThrow(EmailWiederherstellung::ungueltigerLink);
        Konto k = konto(t.username());
        if (!k.enabled() || !k.password().equals(t.passwortStand())) throw ungueltigerLink();
        return t;
    }
    private Versand tokenAnlegen(Konto konto, String adresse, String zweck, Duration dauer) {
        byte[] bytes = new byte[32]; zufall.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("DELETE FROM VERWALTUNG.konto_token WHERE username = :name AND zweck = :zweck").param("name", konto.username()).param("zweck", zweck).update();
        jdbc.sql("INSERT INTO VERWALTUNG.konto_token (token_hash, username, zweck, adresse, passwort_stand, gueltig_bis) VALUES (:hash, :name, :zweck, :adresse, :stand, :bis)")
            .param("hash", hash(token)).param("name", konto.username()).param("zweck", zweck).param("adresse", adresse)
            .param("stand", konto.password()).param("bis", clock.millis() + dauer.toMillis()).update();
        return new Versand(adresse, zweck, token);
    }
    private Konto konto(String name) {
        return jdbc.sql("SELECT username, password, enabled FROM VERWALTUNG.konto WHERE username = :name").param("name", name).query(Konto.class).single();
    }
    private String bestaetigteAdresse(String name) {
        return jdbc.sql("SELECT adresse FROM VERWALTUNG.konto_email WHERE username = :name AND bestaetigt = TRUE").param("name", name).query(String.class).optional().orElse(null);
    }
    private boolean limit(String schluessel, int maximum, Duration dauer) {
        return Boolean.TRUE.equals(tx.execute(status -> { sperren(); return limitInTransaktion(schluessel, maximum, dauer); }));
    }
    private boolean limitInTransaktion(String schluessel, int maximum, Duration dauer) {
        jdbc.sql("DELETE FROM VERWALTUNG.konto_anfragelimit WHERE gueltig_bis <= :jetzt").param("jetzt", clock.millis()).update();
        String key = hash(schluessel);
        var anzahl = jdbc.sql("SELECT anzahl FROM VERWALTUNG.konto_anfragelimit WHERE schluessel = :key").param("key", key).query(Integer.class).optional();
        if (anzahl.orElse(0) >= maximum) return false;
        if (anzahl.isPresent()) jdbc.sql("UPDATE VERWALTUNG.konto_anfragelimit SET anzahl = anzahl + 1 WHERE schluessel = :key").param("key", key).update();
        else jdbc.sql("INSERT INTO VERWALTUNG.konto_anfragelimit (schluessel, anzahl, gueltig_bis) VALUES (:key, 1, :bis)")
            .param("key", key).param("bis", clock.millis() + dauer.toMillis()).update();
        return true;
    }
    private void bereinigen() {
        jdbc.sql("DELETE FROM VERWALTUNG.konto_token WHERE gueltig_bis <= :jetzt").param("jetzt", clock.millis()).update();
        jdbc.sql("DELETE FROM VERWALTUNG.konto_email WHERE bestaetigt = FALSE AND angelegt <= :ab").param("ab", clock.millis() - Duration.ofDays(1).toMillis()).update();
    }
    private void sperren() { jdbc.sql("SELECT id FROM VERWALTUNG.sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single(); }
    private static String eigenerName() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    public static String emailNormalisieren(String adresse) {
        String wert = adresse == null ? "" : adresse.strip().toLowerCase(Locale.ROOT);
        // Bewusste Produktregel: ASCII-Adressen, Vergleich ohne Groß-/Kleinschreibung; keine Provider-Aliasregeln.
        if (wert.length() > 254 || !wert.matches("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                || wert.indexOf('@') > 64 || java.util.Arrays.stream(wert.substring(wert.indexOf('@') + 1).split("\\.")).anyMatch(s -> s.length() > 63))
            throw new IllegalArgumentException("Bitte eine gültige E-Mail-Adresse eingeben (ohne Umlaute; internationale Domains als Punycode).");
        return wert;
    }
    private static String hash(String wert) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wert.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static IllegalArgumentException ungueltigerLink() { return new IllegalArgumentException("Dieser Link ist ungültig oder abgelaufen. Bitte fordern Sie einen neuen Link an."); }
    private void hinweis(String adresse, String text) { einreihen(() -> mail.hinweis(adresse, text)); }
    private void einreihen(Runnable aufgabe) {
        try { versand.execute(() -> { try { aufgabe.run(); } catch (RuntimeException e) { versandfehler(); } }); }
        catch (RejectedExecutionException e) { versandfehler(); }
    }
    private static void versandfehler() { LoggerFactory.getLogger(EmailWiederherstellung.class).warn("Konto-E-Mail konnte nicht verarbeitet werden. SMTP-Konfiguration und Erreichbarkeit prüfen."); }
}
