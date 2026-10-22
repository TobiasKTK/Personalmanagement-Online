package de.pemonline.mandant;

import de.pemonline.fachlich.FachlicherFehler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service @Validated
public class MandantenService {
    public record Speicher(long mandantenspeicherId, String name, String status, long version) {}
    public record Eingabe(@NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cntrl}]*$") String name, @PositiveOrZero Long version) {}
    public record Zuordnung(@NotBlank @Pattern(regexp = "[a-z0-9._-]{3,80}") String name, @NotNull String rolle, boolean aktiv) {}
    public record GlobalesKonto(String name, boolean aktiv, boolean systemverwaltung, List<Mitgliedschaft> zuordnungen) {}
    public record Mitgliedschaft(long mandantenspeicherId, String firma, String rolle, boolean aktiv) {}
    public record KontoEingabe(boolean aktiv, boolean systemverwaltung, @NotNull @Size(max = 128) String passwort) {
        @Override public String toString() { return "KontoEingabe[passwort=[geschützt]]"; }
    }
    private final JdbcClient jdbc;
    private final DataSource basis;
    private final TransactionTemplate transaktion;
    private final SessionRegistry sessions;
    private final PasswordEncoder encoder;
    private final de.pemonline.sicherheit.Anmeldeart anmeldeart;
    public MandantenService(@Qualifier("verwaltungJdbc") JdbcClient jdbc, @Qualifier("basisDataSource") DataSource basis,
            SessionRegistry sessions, PasswordEncoder encoder, de.pemonline.sicherheit.Anmeldeart anmeldeart) {
        this.jdbc = jdbc; this.basis = basis; this.sessions = sessions; this.encoder = encoder;
        this.anmeldeart = anmeldeart;
        transaktion = new TransactionTemplate(new DataSourceTransactionManager(basis));
    }
    @PreAuthorize("hasRole('SYSTEMADMIN')")
    public List<Speicher> liste() {
        return jdbc.sql("SELECT id AS mandantenspeicher_id, name, status, version FROM VERWALTUNG.mandantenspeicher ORDER BY id").query(Speicher.class).list();
    }
    @PreAuthorize("hasRole('SYSTEMADMIN')")
    public Speicher anlegen(@Valid @NotNull Eingabe e) {
        if (e.version() != null) throw new FachlicherFehler(UNGUELTIG, "Bei einer neuen Firma keine Version übermitteln.");
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO VERWALTUNG.mandantenspeicher (name, name_normalisiert, status) VALUES (:name, :normalisiert, 'VORBEREITUNG')")
            .param("name", e.name().strip()).param("normalisiert", e.name().strip().toLowerCase(Locale.ROOT)).update(key, "id");
        long id = key.getKey().longValue();
        return einrichten(id);
    }
    @PreAuthorize("hasRole('SYSTEMADMIN')")
    public Speicher einrichten(@Positive long id) {
        if (finden(id).status().equals("AKTIV")) return finden(id);
        // H2-DDL ist nicht atomar. Ein unvollständiger Speicher bleibt sichtbar, aber für Anmeldungen gesperrt.
        MandantenKonfiguration.fachschemaMigrieren(basis, id);
        transaktion.executeWithoutResult(status -> {
            sperren();
            zuordnen(id, new Zuordnung(aktuellerName(), "ADMIN", true));
            jdbc.sql("UPDATE VERWALTUNG.mandantenspeicher SET status = 'AKTIV' WHERE id = :id").param("id", id).update();
        });
        return finden(id);
    }
    @PreAuthorize("hasRole('SYSTEMADMIN')")
    public Speicher umbenennen(@Positive long id, @Valid @NotNull Eingabe e) {
        if (e.version() == null) throw new FachlicherFehler(UNGUELTIG, "Die aktuelle Version fehlt.");
        if (jdbc.sql("UPDATE VERWALTUNG.mandantenspeicher SET name = :name, name_normalisiert = :normalisiert, version = version + 1 WHERE id = :id AND version = :version")
                .param("name", e.name().strip()).param("normalisiert", e.name().strip().toLowerCase(Locale.ROOT))
                .param("id", id).param("version", e.version()).update() != 1)
            throw new FachlicherFehler(KONFLIKT, "Firma wurde inzwischen geändert. Bitte neu laden.");
        return finden(id);
    }
    @PreAuthorize("hasRole('SYSTEMADMIN')")
    public void zuweisung(@Positive long id, @Valid @NotNull Zuordnung e) {
        if (!Set.of("LESEN", "PERSONAL", "ADMIN").contains(e.rolle())) throw new FachlicherFehler(UNGUELTIG, "Ungültige Rolle.");
        if (!finden(id).status().equals("AKTIV")) throw new FachlicherFehler(KONFLIKT, "Mandantenspeicher ist noch nicht bereit.");
        transaktion.executeWithoutResult(status -> {
            sperren();
            if (jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto WHERE username = :name").param("name", e.name()).query(Long.class).single() == 0)
                throw new FachlicherFehler(NICHT_GEFUNDEN, "Konto nicht gefunden. Zuerst ein Konto anlegen.");
            zuordnen(id, e);
        });
        sitzungenBeenden(e.name());
    }
    private void zuordnen(long id, Zuordnung e) {
        String schema = MandantenKontext.schema(id);
        jdbc.sql("SELECT id FROM " + schema + ".konten_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
        if (jdbc.sql("SELECT COUNT(*) FROM " + schema + ".users WHERE username = :name").param("name", e.name()).query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO " + schema + ".users (username, password, enabled) VALUES (:name, '!', :aktiv)").param("name", e.name()).param("aktiv", e.aktiv()).update();
            jdbc.sql("INSERT INTO " + schema + ".authorities (username, authority) VALUES (:name, :rolle)").param("name", e.name()).param("rolle", "ROLE_" + e.rolle()).update();
        } else {
            jdbc.sql("UPDATE " + schema + ".users SET enabled = :aktiv WHERE username = :name").param("name", e.name()).param("aktiv", e.aktiv()).update();
            jdbc.sql("UPDATE " + schema + ".authorities SET authority = :rolle WHERE username = :name").param("name", e.name()).param("rolle", "ROLE_" + e.rolle()).update();
        }
        jdbc.sql("MERGE INTO VERWALTUNG.konto_mandant (username, mandantenspeicher_id) KEY (username, mandantenspeicher_id) VALUES (:name, :id)")
            .param("name", e.name()).param("id", id).update();
    }
    @PreAuthorize("hasRole('SYSTEMADMIN')")
    public List<GlobalesKonto> konten() {
        return jdbc.sql("SELECT username, enabled, systemverwaltung FROM VERWALTUNG.konto ORDER BY username")
            .query((rs, n) -> new GlobalesKonto(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), List.of())).list().stream()
            .map(k -> new GlobalesKonto(k.name(), k.aktiv(), k.systemverwaltung(), mitgliedschaften(k.name()))).toList();
    }
    private List<Mitgliedschaft> mitgliedschaften(String name) {
        return jdbc.sql("SELECT m.id, m.name FROM VERWALTUNG.mandantenspeicher m JOIN VERWALTUNG.konto_mandant k ON k.mandantenspeicher_id = m.id WHERE k.username = :name AND m.status = 'AKTIV' ORDER BY m.id")
            .param("name", name).query((rs, n) -> new Mitgliedschaft(rs.getLong(1), rs.getString(2), null, false)).list().stream().map(m -> {
                long id = m.mandantenspeicherId(); String firma = m.firma(), schema = MandantenKontext.schema(id);
                return jdbc.sql("SELECT SUBSTRING(a.authority, 6) AS rolle, u.enabled FROM " + schema + ".users u JOIN " + schema + ".authorities a ON a.username = u.username WHERE u.username = :name")
                    .param("name", name).query((r, k) -> new Mitgliedschaft(id, firma, r.getString(1), r.getBoolean(2))).single();
            }).toList();
    }
    @PreAuthorize("hasRole('SYSTEMADMIN')")
    public void kontoAendern(@NotBlank String name, @Valid @NotNull KontoEingabe e) {
        if (anmeldeart.keycloak() && !e.passwort().isEmpty())
            throw new FachlicherFehler(UNGUELTIG, "Passwörter werden ausschließlich in Keycloak verwaltet. Das Passwortfeld muss leer bleiben.");
        if (name.equals(aktuellerName()) && (!e.aktiv() || !e.systemverwaltung()))
            throw new FachlicherFehler(KONFLIKT, "Das eigene Systemverwaltungskonto muss aktiv bleiben.");
        if (!e.passwort().isEmpty() && e.passwort().length() < 12) throw new FachlicherFehler(UNGUELTIG, "Passwörter müssen 12–128 Zeichen enthalten.");
        transaktion.executeWithoutResult(status -> {
            sperren();
            if (jdbc.sql("UPDATE VERWALTUNG.konto SET enabled = :aktiv, systemverwaltung = :system WHERE username = :name")
                    .param("name", name).param("aktiv", e.aktiv()).param("system", e.systemverwaltung()).update() == 0)
                throw new FachlicherFehler(NICHT_GEFUNDEN, "Konto nicht gefunden.");
            if (!e.passwort().isEmpty()) jdbc.sql("UPDATE VERWALTUNG.konto SET password = :passwort WHERE username = :name")
                .param("name", name).param("passwort", encoder.encode(e.passwort())).update();
        });
        sitzungenBeenden(name);
    }
    private Speicher finden(long id) {
        return jdbc.sql("SELECT id AS mandantenspeicher_id, name, status, version FROM VERWALTUNG.mandantenspeicher WHERE id = :id")
            .param("id", id).query(Speicher.class).optional().orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Firma nicht gefunden."));
    }
    private String aktuellerName() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    private void sperren() { jdbc.sql("SELECT id FROM VERWALTUNG.sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single(); }
    private void sitzungenBeenden(String name) {
        for (var principal : sessions.getAllPrincipals()) if (principal instanceof UserDetails u && u.getUsername().equals(name))
            sessions.getAllSessions(principal, false).forEach(s -> s.expireNow());
    }
}
