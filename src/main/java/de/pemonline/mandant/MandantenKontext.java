package de.pemonline.mandant;

import org.springframework.security.core.context.SecurityContextHolder;

public final class MandantenKontext {
    private static final ThreadLocal<Long> INTERN = new ThreadLocal<>();
    private MandantenKontext() {}
    public static long id() {
        Long id = INTERN.get();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (id == null && auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof MandantenBenutzer b)
            id = b.mandantenspeicherId();
        if (id == null) throw new IllegalStateException("Kein aktiver Mandantenspeicher.");
        return id;
    }
    public static String schema(long id) {
        if (id < 1) throw new IllegalArgumentException("Ungültige Mandantenspeicher-ID.");
        return id == 1 ? "PUBLIC" : "MANDANT_" + id;
    }
    /** Nur für Initialisierung und Hintergrundaufgaben; HTTP-Eingaben dürfen diesen Kontext nicht setzen. */
    public static AutoCloseable intern(long id) {
        schema(id);
        Long vorher = INTERN.get();
        INTERN.set(id);
        return () -> { if (vorher == null) INTERN.remove(); else INTERN.set(vorher); };
    }
}
