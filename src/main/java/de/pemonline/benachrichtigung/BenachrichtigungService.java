package de.pemonline.benachrichtigung;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.urlaub.Urlaubsantrag;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;

@Service @Validated @Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
public class BenachrichtigungService {
    public record Eintrag(long id, long antragId, String text, OffsetDateTime erstellt, boolean gelesen) {}
    public record Seite(List<Eintrag> eintraege, long gesamt, long ungelesen) {}
    private final JdbcClient jdbc;
    private final Clock clock;
    public BenachrichtigungService(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    private String konto() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    public long ungelesen() {
        return jdbc.sql("SELECT COUNT(*) FROM benachrichtigung WHERE konto = :konto AND NOT gelesen").param("konto", konto()).query(Long.class).single();
    }
    public Seite liste(boolean nurUngelesen, @Min(0) int seite) {
        String where = " FROM benachrichtigung WHERE konto = :konto AND (:nur = FALSE OR NOT gelesen)";
        var eintraege = jdbc.sql("SELECT id, antrag_id, text, erstellt, gelesen" + where + " ORDER BY id DESC LIMIT 20 OFFSET :offset")
            .param("konto", konto()).param("nur", nurUngelesen).param("offset", (long) seite * 20).query(Eintrag.class).list();
        long gesamt = jdbc.sql("SELECT COUNT(*)" + where).param("konto", konto()).param("nur", nurUngelesen).query(Long.class).single();
        return new Seite(eintraege, gesamt, ungelesen());
    }
    @Transactional public void gelesen(@Positive long id) {
        if (jdbc.sql("UPDATE benachrichtigung SET gelesen = TRUE WHERE id = :id AND konto = :konto").param("id", id).param("konto", konto()).update() != 1)
            throw new FachlicherFehler(FachlicherFehler.Art.NICHT_GEFUNDEN, "Benachrichtigung nicht gefunden.");
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void versenden(Urlaubsantrag antrag, Collection<String> empfaenger, String text) {
        for (String konto : new HashSet<>(empfaenger)) {
            jdbc.sql("""
                INSERT INTO benachrichtigung (konto, antrag_id, antrag_version, text, erstellt)
                SELECT u.username, :antrag, :version, :text, :zeit FROM users u
                JOIN VERWALTUNG.konto k ON k.username = u.username
                WHERE u.username = :konto AND u.enabled = TRUE AND k.enabled = TRUE
                AND NOT EXISTS (SELECT 1 FROM benachrichtigung WHERE konto = :konto AND antrag_id = :antrag AND antrag_version = :version)
                """).param("konto", konto).param("antrag", antrag.id()).param("version", antrag.version()).param("text", text)
                .param("zeit", OffsetDateTime.now(clock)).update();
        }
    }
}
