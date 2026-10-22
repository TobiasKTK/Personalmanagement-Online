package de.pemonline.sicherheit;

import de.pemonline.mandant.Anmeldekonten;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "pem.anmeldung", havingValue = "keycloak", matchIfMissing = true)
public class KeycloakKonfiguration {
    @Bean
    ClientRegistrationRepository keycloakRegistrierung(
            @Value("${pem.keycloak.issuer:http://127.0.0.1:8180/realms/pemonline}") String issuer,
            @Value("${pem.keycloak.client-id:pemonline}") String clientId,
            @Value("${pem.keycloak.client-secret:}") String clientSecret) {
        var registrierung = ClientRegistrations.fromIssuerLocation(issuer)
                .registrationId("keycloak").clientName("Keycloak")
                .clientId(clientId).clientSecret(clientSecret)
                .clientAuthenticationMethod(clientSecret.isBlank()
                        ? ClientAuthenticationMethod.NONE : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .scope("openid", "profile", "email")
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").build();
        return new InMemoryClientRegistrationRepository(registrierung);
    }

    @Bean KeycloakBenutzerService keycloakBenutzerService(Anmeldekonten konten) {
        return new KeycloakBenutzerService(konten);
    }
}
