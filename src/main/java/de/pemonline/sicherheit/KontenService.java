package de.pemonline.sicherheit;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.mandant.MandantenKontext;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@Transactional(readOnly = true)
public class KontenService {
    public record Konto(String name, String rolle, boolean aktiv) {}
    public record Eingabe(
            @NotBlank(message = "Bitte einen Kontonamen eingeben.")
            @Pattern(regexp = "[a-z0-9._-]{3,80}", message = "Kontonamen müssen 3–80 Zeichen enthalten. Erlaubt sind nur Kleinbuchstaben (a–z), Ziffern, Punkt, Unterstrich und Bindestrich.") String name,
            @NotNull(message = "Bitte ein Passwort übermitteln; beim Bearbeiten darf es leer bleiben.") String passwort,
            @NotNull(message = "Bitte eine Rolle auswählen.") String rolle, boolean aktiv) {
        @Override public String toString() { return "Kontoeingabe[name=" + name + ", passwort=[geschützt], rolle=" + rolle + ", aktiv=" + aktiv + "]"; }
    }
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final Anmeldeart anmeldeart;
    public KontenService(JdbcClient jdbc, PasswordEncoder encoder, SessionRegistry sessions, Anmeldeart anmeldeart) {
        this.jdbc = jdbc; this.encoder = encoder; this.sessions = sessions; this.anmeldeart = anmeldeart;
    }
    @PreAuthorize("hasRole('ADMIN')")
    public List<Konto> liste() {
        return jdbc.sql("SELECT u.username AS name, SUBSTRING(a.authority, 6) AS rolle, u.enabled AS aktiv FROM users u JOIN authorities a ON a.username = u.username ORDER BY u.username")
                .query(Konto.class).list();
    }
    @Transactional
    void initialisieren(String name, String passwort) {
        sperren();
        if (jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto").query(Long.class).single() > 0) return;
        if (!name.matches("[a-z0-9._-]{3,80}"))
            throw new IllegalStateException("MITARBEITER_ADMIN_NAME muss 3–80 Zeichen enthalten: a-z, 0-9, ._-");
        if (!anmeldeart.keycloak() && (passwort.length() < 12 || passwort.length() > 128))
            throw new IllegalStateException("Für den ersten Start MITARBEITER_ADMIN_NAME (3–80 Zeichen: a-z, 0-9, ._-) und MITARBEITER_ADMIN_PASSWORT (12–128 Zeichen) setzen.");
        jdbc.sql("INSERT INTO VERWALTUNG.konto (username, password, enabled, systemverwaltung) VALUES (:name, :passwort, TRUE, TRUE)")
            .param("name", name).param("passwort", anmeldeart.keycloak() ? "!" : encoder.encode(passwort)).update();
        zuordnen(name, "ADMIN", true);
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void speichern(@Valid @NotNull Eingabe e, boolean neu) {
        String angemeldet = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        sperren();
        if (!Set.of("LESEN", "PERSONAL", "ADMIN", "MITARBEITER", "TEAMLEITUNG").contains(e.rolle()))
            throw new FachlicherFehler(UNGUELTIG, "Ungültige Rolle.");
        boolean existiert = jdbc.sql("SELECT COUNT(*) FROM users WHERE username = :name").param("name", e.name()).query(Long.class).single() > 0;
        if (neu && jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto WHERE username = :name").param("name", e.name()).query(Long.class).single() > 0)
            throw new FachlicherFehler(KONFLIKT, "Der Kontoname ist bereits vergeben. Kontonamen sind mandantenübergreifend eindeutig.");
        if (!neu && !existiert) throw new FachlicherFehler(NICHT_GEFUNDEN, "Konto nicht gefunden.");
        if (e.name().equals(angemeldet) && (!e.aktiv() || !e.rolle().equals("ADMIN")))
            throw new FachlicherFehler(KONFLIKT, "Das eigene Administrationskonto muss aktiv und Administrator bleiben.");
        if (anmeldeart.keycloak() && !e.passwort().isEmpty())
            throw new FachlicherFehler(UNGUELTIG, "Passwörter werden ausschließlich in Keycloak verwaltet. Das Passwortfeld muss leer bleiben.");
        if (!anmeldeart.keycloak() && (neu || !e.passwort().isEmpty()) && (e.passwort().length() < 12 || e.passwort().length() > 128))
            throw new FachlicherFehler(UNGUELTIG, "Passwörter müssen 12–128 Zeichen enthalten.");
        if (neu) {
            jdbc.sql("INSERT INTO VERWALTUNG.konto (username, password, enabled) VALUES (:name, :passwort, TRUE)")
                .param("name", e.name()).param("passwort", anmeldeart.keycloak() ? "!" : encoder.encode(e.passwort())).update();
            zuordnen(e.name(), e.rolle(), e.aktiv());
        } else {
            if (!e.passwort().isEmpty()) {
                boolean systemverwaltung = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                    .getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEMADMIN"));
                if (!systemverwaltung && (jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_mandant WHERE username = :name").param("name", e.name()).query(Long.class).single() > 1
                        || jdbc.sql("SELECT systemverwaltung FROM VERWALTUNG.konto WHERE username = :name").param("name", e.name()).query(Boolean.class).single()))
                    throw new FachlicherFehler(KONFLIKT, "Das Passwort dieses mandantenübergreifenden Kontos kann nur die Systemverwaltung ändern.");
                jdbc.sql("UPDATE VERWALTUNG.konto SET password = :passwort WHERE username = :name").param("name", e.name()).param("passwort", encoder.encode(e.passwort())).update();
            }
            jdbc.sql("UPDATE users SET enabled = :aktiv WHERE username = :name").param("name", e.name()).param("aktiv", e.aktiv()).update();
            jdbc.sql("UPDATE authorities SET authority = :rolle WHERE username = :name").param("name", e.name()).param("rolle", "ROLE_" + e.rolle()).update();
            if (!e.rolle().equals("TEAMLEITUNG")) {
                jdbc.sql("DELETE FROM teamleitung_organisation WHERE konto = :name").param("name", e.name()).update();
                jdbc.sql("UPDATE teamleitung_stand SET version = version + 1 WHERE konto = :name").param("name", e.name()).update();
            }
        }
        // Bestehende Sitzungen dürfen entfernte Rechte nicht weiterverwenden.
        if (!neu) for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof UserDetails details && details.getUsername().equals(e.name()))
                sessions.getAllSessions(principal, false).forEach(s -> s.expireNow());
        }
    }
    private void zuordnen(String name, String rolle, boolean aktiv) {
        jdbc.sql("INSERT INTO users (username, password, enabled) VALUES (:name, '!', :aktiv)").param("name", name).param("aktiv", aktiv).update();
        jdbc.sql("INSERT INTO authorities (username, authority) VALUES (:name, :rolle)").param("name", name).param("rolle", "ROLE_" + rolle).update();
        jdbc.sql("INSERT INTO VERWALTUNG.konto_mandant (username, mandantenspeicher_id) VALUES (:name, :id)")
            .param("name", name).param("id", MandantenKontext.id()).update();
    }
    private void sperren() {
        jdbc.sql("SELECT id FROM VERWALTUNG.sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
        jdbc.sql("SELECT id FROM konten_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
    }
}
