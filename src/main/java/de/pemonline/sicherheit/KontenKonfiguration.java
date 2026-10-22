package de.pemonline.sicherheit;

import de.pemonline.mandant.MandantenKontext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

@Configuration
@EnableMethodSecurity
public class KontenKonfiguration {
    @Bean PasswordEncoder passwordEncoder() { return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean SessionRegistry sessionRegistry() { return new SessionRegistryImpl(); }
    @Bean ApplicationRunner erstesKonto(KontenService konten,
            @Value("${mitarbeiter.admin-name:admin}") String name,
            @Value("${mitarbeiter.admin-passwort:}") String passwort) {
        return args -> {
            var kontext = MandantenKontext.intern(1);
            try {
                konten.initialisieren(name, passwort);
            } finally {
                kontext.close();
            }
        };
    }
}
