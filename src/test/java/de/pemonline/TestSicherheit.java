package de.pemonline;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

final class TestSicherheit {
    static void anmelden(String rolle) {
        var benutzer = new de.pemonline.mandant.MandantenBenutzer("test-" + rolle, "", true, 1L, rolle, false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                benutzer, null, benutzer.getAuthorities()));
    }
    static void abmelden() { SecurityContextHolder.clearContext(); }
}
