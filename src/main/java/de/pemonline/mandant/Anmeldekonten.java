package de.pemonline.mandant;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class Anmeldekonten implements UserDetailsService {
    public record Zugang(long mandantenspeicherId, String name, String rolle) {}
    public record Identitaet(String username, String password, boolean enabled, boolean systemverwaltung) {}
    private final JdbcClient jdbc;
    public Anmeldekonten(@Qualifier("verwaltungJdbc") JdbcClient jdbc) { this.jdbc = jdbc; }
    public Identitaet identitaet(String name) {
        return jdbc.sql("SELECT * FROM VERWALTUNG.konto WHERE username = :name").param("name", name)
            .query(Identitaet.class).optional().orElseThrow(() -> new UsernameNotFoundException("Konto nicht gefunden."));
    }
    public List<Zugang> zugaenge(String name) {
        var mandanten = jdbc.sql("SELECT m.id, m.name FROM VERWALTUNG.mandantenspeicher m JOIN VERWALTUNG.konto_mandant k ON k.mandantenspeicher_id = m.id WHERE k.username = :name AND m.status = 'AKTIV' ORDER BY m.id")
            .param("name", name).query((rs, n) -> new Zugang(rs.getLong("id"), rs.getString("name"), null)).list();
        return mandanten.stream().flatMap(m -> rolle(name, m.mandantenspeicherId()).stream().map(r -> new Zugang(m.mandantenspeicherId(), m.name(), r))).toList();
    }
    public Optional<String> rolle(String name, long id) {
        String schema = MandantenKontext.schema(id);
        if (jdbc.sql("SELECT COUNT(*) FROM VERWALTUNG.konto_mandant k JOIN VERWALTUNG.mandantenspeicher m ON m.id = k.mandantenspeicher_id WHERE k.username = :name AND m.id = :id AND m.status = 'AKTIV'")
                .param("name", name).param("id", id).query(Long.class).single() == 0) return Optional.empty();
        return jdbc.sql("SELECT SUBSTRING(a.authority, 6) FROM " + schema + ".users u JOIN " + schema + ".authorities a ON a.username = u.username WHERE u.username = :name AND u.enabled = TRUE")
            .param("name", name).query(String.class).optional();
    }
    @Override public MandantenBenutzer loadUserByUsername(String name) {
        name = name == null ? "" : name.strip();
        if (name.contains("@")) {
            String adresse;
            try { adresse = de.pemonline.sicherheit.EmailWiederherstellung.emailNormalisieren(name); }
            catch (IllegalArgumentException e) { throw new UsernameNotFoundException("Konto nicht gefunden."); }
            name = jdbc.sql("SELECT username FROM VERWALTUNG.konto_email WHERE adresse = :adresse AND bestaetigt = TRUE")
                .param("adresse", adresse).query(String.class).optional().orElseThrow(() -> new UsernameNotFoundException("Konto nicht gefunden."));
        }
        var identitaet = identitaet(name);
        var zugang = zugaenge(name).stream().findFirst().orElse(null);
        if (zugang == null && !identitaet.systemverwaltung()) throw new DisabledException("Kein aktiver Mandantenzugang.");
        return benutzer(identitaet, zugang == null ? null : zugang.mandantenspeicherId(), zugang == null ? null : zugang.rolle());
    }
    public MandantenBenutzer fuerMandant(String name, long id) {
        var identitaet = identitaet(name);
        if (!identitaet.enabled()) throw new DisabledException("Konto ist deaktiviert.");
        String rolle = rolle(name, id).orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Kein Zugriff auf diesen Mandanten."));
        return benutzer(identitaet, id, rolle);
    }
    private MandantenBenutzer benutzer(Identitaet konto, Long id, String rolle) {
        return new MandantenBenutzer(konto.username(), konto.password(), konto.enabled(), id, rolle, konto.systemverwaltung());
    }
}
