package de.pemonline.sicherheit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keycloak ist der Standard; die lokale Anmeldung dient den isolierten Bestandstests. */
@Component
public final class Anmeldeart {
    private final boolean keycloak;

    public Anmeldeart(@Value("${pem.anmeldung:keycloak}") String art) {
        if (!art.equals("keycloak") && !art.equals("lokal"))
            throw new IllegalArgumentException("pem.anmeldung muss keycloak oder lokal sein.");
        keycloak = art.equals("keycloak");
    }

    public boolean keycloak() { return keycloak; }
}
