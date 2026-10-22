package de.pemonline.mandant;

import de.pemonline.sicherheit.KeycloakBenutzer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MandantenBenutzerTest {
    @Test void firmenwechselErneuertKeineVeralteteAuthentifizierung() {
        var angemeldet = new MandantenBenutzer("testkonto", "alter-gesalzener-db-hash", true, 1L, "LESEN", false);
        angemeldet.eraseCredentials();
        assertThat(angemeldet.getPassword()).isNull();
        assertThat(angemeldet.passwortAktuell("alter-gesalzener-db-hash")).isTrue();
        var aktuellerZugang = new MandantenBenutzer("testkonto", "neuer-gesalzener-db-hash", true, 2L, "PERSONAL", false);
        var gewechselt = new MandantenBenutzer(aktuellerZugang, angemeldet);
        gewechselt.eraseCredentials();
        assertThat(gewechselt.mandantenspeicherId()).isEqualTo(2L);
        assertThat(gewechselt.rolle()).isEqualTo("PERSONAL");
        assertThat(gewechselt.passwortAktuell("neuer-gesalzener-db-hash")).isFalse();
        assertThat(gewechselt.passwortAktuell("alter-gesalzener-db-hash")).isTrue();
        var keycloak = new KeycloakBenutzer(gewechselt, mock(OidcUser.class));
        keycloak.eraseCredentials();
        assertThat(keycloak.passwortAktuell("neuer-gesalzener-db-hash")).isFalse();
        assertThat(keycloak.passwortAktuell("alter-gesalzener-db-hash")).isTrue();
    }
}
