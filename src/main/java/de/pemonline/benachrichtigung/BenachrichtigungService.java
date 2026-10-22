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
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN', 'MITARBEITER', 'TEAMLEITUNG')")
public class BenachrichtigungService {
    public record Eintrag(long id, Long antragId, String text, OffsetDateTime erstellt, boolean gelesen, String ziel) {}
    public record Seite(List<Eintrag> eintraege, long gesamt, long ungelesen) {}
    private final JdbcClient jdbc;
    private final Clock clock;
    public BenachrichtigungService(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    private String konto() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    private static final String ALLE = """
        (SELECT id, konto, antrag_id, text, erstellt, gelesen, CAST(NULL AS VARCHAR(500)) AS ziel FROM benachrichtigung
         UNION ALL SELECT -id, konto, CAST(NULL AS BIGINT), text, erstellt, gelesen, ziel FROM personalhinweis
         WHERE (ziel NOT LIKE '/fristen%' AND ziel NOT LIKE '/self-service/pruefung%' AND ziel NOT LIKE '/mitarbeiter%' AND ziel NOT LIKE '/kenntnisse%')
            OR EXISTS (SELECT 1 FROM authorities a WHERE a.username = personalhinweis.konto AND a.authority IN ('ROLE_PERSONAL', 'ROLE_ADMIN')))
        """;
    private static final String SICHTBAR = """
        AND (NOT EXISTS (SELECT 1 FROM authorities nr WHERE nr.username=:konto AND nr.authority IN ('ROLE_MITARBEITER','ROLE_TEAMLEITUNG'))
          OR (n.antrag_id IS NULL AND (n.ziel = '/self-service' OR n.ziel LIKE '/self-service?%' OR n.ziel LIKE '/self-service#%'))
          OR EXISTS (SELECT 1 FROM urlaubsantrag ba LEFT JOIN urlaub_zuordnung bz ON bz.mitarbeiter_id=ba.mitarbeiter_id
             WHERE ba.id=n.antrag_id AND (bz.konto=:konto OR :konto IN (
        """ + de.pemonline.urlaub.UrlaubsantragRepository.genehmigerSql("ba.mitarbeiter_id", "ba.antragsteller") + "))))";
    public long ungelesen() {
        return jdbc.sql("SELECT COUNT(*) FROM " + ALLE + " n WHERE konto = :konto AND NOT gelesen " + SICHTBAR)
            .param("konto", konto()).param("heute", LocalDate.now(clock)).query(Long.class).single();
    }
    public Seite liste(boolean nurUngelesen, @Min(0) int seite) {
        String where = " FROM " + ALLE + " n WHERE konto = :konto AND (:nur = FALSE OR NOT gelesen)" + SICHTBAR;
        var eintraege = jdbc.sql("SELECT id, antrag_id, text, erstellt, gelesen, ziel" + where + " ORDER BY erstellt DESC, id DESC LIMIT 20 OFFSET :offset")
            .param("konto", konto()).param("heute", LocalDate.now(clock)).param("nur", nurUngelesen).param("offset", (long) seite * 20).query(Eintrag.class).list();
        long gesamt = jdbc.sql("SELECT COUNT(*)" + where).param("konto", konto()).param("heute", LocalDate.now(clock)).param("nur", nurUngelesen).query(Long.class).single();
        return new Seite(eintraege, gesamt, ungelesen());
    }
    @Transactional public void gelesen(long id) {
        if (id == 0 || id == Long.MIN_VALUE) throw new FachlicherFehler(FachlicherFehler.Art.NICHT_GEFUNDEN, "Benachrichtigung nicht gefunden.");
        String tabelle = id < 0 ? "personalhinweis" : "benachrichtigung";
        if (jdbc.sql("UPDATE " + tabelle + " SET gelesen = TRUE WHERE id = :id AND konto = :konto").param("id", Math.abs(id)).param("konto", konto()).update() != 1)
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
