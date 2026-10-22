package de.pemonline.mandant;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Identität und Rechte für genau den in dieser Sitzung aktiven Mandanten. */
public class MandantenBenutzer extends User {
    private final Long mandantenspeicherId;
    private final String rolle;
    private final boolean systemverwaltung;
    private final byte[] passwortStand;

    public MandantenBenutzer(String name, String passwort, boolean aktiv, Long mandantenspeicherId,
            String rolle, boolean systemverwaltung) {
        this(name, passwort, aktiv, mandantenspeicherId, rolle, systemverwaltung, fingerprint(passwort));
    }
    private MandantenBenutzer(String name, String passwort, boolean aktiv, Long mandantenspeicherId,
            String rolle, boolean systemverwaltung, byte[] passwortStand) {
        super(name, passwort, aktiv, true, true, true, rechte(rolle, systemverwaltung));
        this.mandantenspeicherId = mandantenspeicherId;
        this.rolle = rolle;
        this.systemverwaltung = systemverwaltung;
        this.passwortStand = passwortStand.clone();
    }
    /** Neue Firmenrechte, aber derselbe Authentifizierungsstand wie bei der Anmeldung. */
    public MandantenBenutzer(MandantenBenutzer zugang, MandantenBenutzer anmeldung) {
        this(zugang.getUsername(), "", zugang.isEnabled(), zugang.mandantenspeicherId(),
                zugang.rolle(), zugang.systemverwaltung(), anmeldung.passwortStand);
        if (!zugang.getUsername().equals(anmeldung.getUsername()))
            throw new IllegalArgumentException("Firmenzugang und Anmeldung müssen zum selben Konto gehören.");
    }
    public boolean passwortAktuell(String gespeichertesPasswort) {
        return MessageDigest.isEqual(passwortStand, fingerprint(gespeichertesPasswort));
    }
    private static byte[] fingerprint(String gespeichertesPasswort) {
        // Fingerprint des gesalzenen DB-Hashes, kein Passwort. Bleibt nach eraseCredentials erhalten.
        try { return MessageDigest.getInstance("SHA-256").digest(gespeichertesPasswort.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static java.util.List<GrantedAuthority> rechte(String rolle, boolean systemverwaltung) {
        var rechte = new ArrayList<GrantedAuthority>();
        if (rolle != null) rechte.add(new SimpleGrantedAuthority("ROLE_" + rolle));
        if (systemverwaltung) rechte.add(new SimpleGrantedAuthority("ROLE_SYSTEMADMIN"));
        return rechte;
    }
    public Long mandantenspeicherId() { return mandantenspeicherId; }
    public String rolle() { return rolle; }
    public boolean systemverwaltung() { return systemverwaltung; }
}
