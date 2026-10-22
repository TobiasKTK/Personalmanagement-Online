package de.pemonline.sicherheit;

import de.pemonline.mandant.MandantenBenutzer;
import java.util.Map;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Fachliche Identität mit den OIDC-Daten für den gemeinsamen Logout. */
public final class KeycloakBenutzer extends MandantenBenutzer implements OidcUser {
    private final OidcUser oidc;

    public KeycloakBenutzer(MandantenBenutzer konto, OidcUser oidc) {
        super(konto, konto);
        this.oidc = oidc;
    }

    @Override public String getName() { return getUsername(); }
    @Override public Map<String, Object> getAttributes() { return oidc.getAttributes(); }
    @Override public Map<String, Object> getClaims() { return oidc.getClaims(); }
    @Override public OidcIdToken getIdToken() { return oidc.getIdToken(); }
    @Override public OidcUserInfo getUserInfo() { return oidc.getUserInfo(); }
}
