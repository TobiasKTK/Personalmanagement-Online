package de.pemonline.sicherheit;

import de.pemonline.mandant.Anmeldekonten;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class KeycloakBenutzerService extends OidcUserService {
    private final Anmeldekonten konten;

    public KeycloakBenutzerService(Anmeldekonten konten) { this.konten = konten; }

    @Override public OidcUser loadUser(OidcUserRequest request) {
        return zuordnen(super.loadUser(request));
    }

    public KeycloakBenutzer zuordnen(OidcUser oidc) {
        // Nur der signierte ID-Token zählt. Weder E-Mail noch frei änderbarer Anzeigename
        // dürfen ein lokales Konto auswählen. Den Claim pflegt ausschließlich die IdP-Administration.
        Object wert = oidc.getIdToken().getClaims().get("pemonline_konto");
        if (!(wert instanceof String name) || !name.matches("[a-z0-9._-]{3,80}"))
            throw abgewiesen();
        try {
            var konto = konten.loadUserByUsername(name);
            if (!konto.isEnabled()) throw abgewiesen();
            return new KeycloakBenutzer(konto, oidc);
        } catch (AuthenticationException e) {
            throw abgewiesen();
        }
    }

    private static OAuth2AuthenticationException abgewiesen() {
        return new OAuth2AuthenticationException(new OAuth2Error("konto_nicht_freigeschaltet"),
                "Kein freigeschalteter pemonline-Zugang. Bitte die Administration kontaktieren.");
    }
}
