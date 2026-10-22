package de.pemonline.mandant;

import java.io.IOException;
import java.util.Objects;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class MandantenFilter extends OncePerRequestFilter {
    private final Anmeldekonten konten;
    public MandantenFilter(Anmeldekonten konten) { this.konten = konten; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MandantenBenutzer b) {
            boolean gueltig;
            try {
                var konto = konten.identitaet(b.getUsername());
                gueltig = konto.enabled() && b.passwortAktuell(konto.password()) && konto.systemverwaltung() == b.systemverwaltung()
                    && (b.mandantenspeicherId() == null || Objects.equals(b.rolle(), konten.rolle(b.getUsername(), b.mandantenspeicherId()).orElse(null)));
            } catch (UsernameNotFoundException e) { gueltig = false; }
            if (!gueltig) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                if (request.getRequestURI().startsWith("/api/")) fehler(response, 401, "Berechtigung geändert. Bitte erneut anmelden.");
                else response.sendRedirect("/login");
                return;
            }
            String pfad = request.getRequestURI();
            boolean fachlich = pfad.startsWith("/api/") && !pfad.equals("/api/sitzung")
                && !pfad.equals("/api/mandanten") && !pfad.startsWith("/api/mandanten/") && !pfad.startsWith("/api/mandantenverwaltung");
            if (fachlich) {
                if (b.mandantenspeicherId() == null) { fehler(response, 403, "Bitte zuerst einen Mandanten auswählen."); return; }
                if (!String.valueOf(b.mandantenspeicherId()).equals(request.getHeader("X-Mandantenspeicher-ID"))) {
                    fehler(response, 409, "Der Mandantenspeicher fehlt oder wurde gewechselt. Bitte die Seite neu laden."); return;
                }
            }
        }
        chain.doFilter(request, response);
    }
    private static void fehler(HttpServletResponse response, int status, String meldung) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"meldung\":\"" + meldung + "\"}");
    }
}
