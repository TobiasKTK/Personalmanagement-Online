package de.pemonline.benachrichtigung;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Interner Versand; Empfänger und fachliche Rechte bestimmt der aufrufende Service. */
@Service
public class PersonalhinweisService {
    private final JdbcClient jdbc;
    private final Clock clock;
    public PersonalhinweisService(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void senden(String konto, String schluessel, String text, String ziel) {
        if (konto == null) return;
        if (schluessel == null || schluessel.isBlank() || schluessel.length() > 240 || text == null || text.length() > 1000
                || ziel == null || ziel.length() > 500 || !ziel.matches("/(fristen|personalprozesse|self-service|mitarbeiter|kenntnisse)([/?#].*)?"))
            throw new IllegalArgumentException("Ungültiger interner Hinweis.");
        // Serialisiert konkurrierende Versuche vor der Existenzprüfung, auch zwischen Prozessen.
        jdbc.sql("SELECT id FROM personalhinweis_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single();
        jdbc.sql("""
            INSERT INTO personalhinweis(konto, schluessel, text, ziel, erstellt)
            SELECT u.username, :key, :text, :ziel, :zeit FROM users u
            JOIN VERWALTUNG.konto k ON k.username = u.username
            WHERE u.username = :konto AND u.enabled AND k.enabled
            AND NOT EXISTS (SELECT 1 FROM personalhinweis WHERE konto = :konto AND schluessel = :key)
            """).param("konto", konto).param("key", schluessel).param("text", text).param("ziel", ziel)
            .param("zeit", OffsetDateTime.now(clock)).update();
    }
}
