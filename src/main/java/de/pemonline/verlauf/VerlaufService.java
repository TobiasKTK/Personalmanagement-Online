package de.pemonline.verlauf;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@org.springframework.validation.annotation.Validated
public class VerlaufService {
    public record Eintrag(long id, long mitarbeiterId, Instant zeitpunkt, String bearbeiter, String aktion, String vorher, String nachher) {}
    public record Seite(List<Eintrag> eintraege, long gesamt) {}
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final java.time.Clock clock;
    public VerlaufService(JdbcClient jdbc, ObjectMapper json, java.time.Clock clock) { this.jdbc = jdbc; this.json = json; this.clock = clock; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void speichern(long mitarbeiterId, String aktion, Object vorher, Object nachher) {
        jdbc.sql("INSERT INTO mitarbeiter_verlauf (mitarbeiter_id, zeitpunkt, bearbeiter, aktion, vorher, nachher) VALUES (:id, :zeit, :bearbeiter, :aktion, :vorher, :nachher)")
                .param("id", mitarbeiterId).param("zeit", java.time.OffsetDateTime.now(clock))
                .param("bearbeiter", bearbeiter()).param("aktion", aktion)
                .param("vorher", vorher == null ? null : json.writeValueAsString(vorher), java.sql.Types.CLOB)
                .param("nachher", nachher == null ? null : json.writeValueAsString(nachher), java.sql.Types.CLOB).update();
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void organisationUmbenannt(long organisationId, String vorher, String nachher) {
        // Eine mengenbasierte Abfrage hält auch Umbenennungen großer Abteilungen nachvollziehbar.
        jdbc.sql("""
                INSERT INTO mitarbeiter_verlauf (mitarbeiter_id, zeitpunkt, bearbeiter, aktion, vorher, nachher)
                SELECT id, :zeit, :bearbeiter, 'ORGANISATION_UMBENANNT', :vorher, :nachher
                FROM mitarbeiter m WHERE m.organisationseinheit_id = :organisation
                  OR EXISTS (SELECT 1 FROM mitarbeiter_historie h WHERE h.mitarbeiter_id = m.id AND h.organisationseinheit_id = :organisation)
                """)
                .param("organisation", organisationId).param("zeit", java.time.OffsetDateTime.now(clock))
                .param("bearbeiter", bearbeiter())
                .param("vorher", json.writeValueAsString(java.util.Map.of("organisationseinheit", vorher)))
                .param("nachher", json.writeValueAsString(java.util.Map.of("organisationseinheit", nachher))).update();
    }
    private String bearbeiter() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "SYSTEM" : auth.getName();
    }
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
    public Seite liste(@jakarta.validation.constraints.Positive long id,
            @jakarta.validation.constraints.Min(0) int seite,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int groesse) {
        var eintraege = jdbc.sql("SELECT * FROM mitarbeiter_verlauf WHERE mitarbeiter_id = :id ORDER BY id DESC LIMIT :limit OFFSET :offset")
                .param("id", id).param("limit", groesse).param("offset", (long) seite * groesse)
                .query((rs, n) -> new Eintrag(rs.getLong("id"), rs.getLong("mitarbeiter_id"), rs.getTimestamp("zeitpunkt").toInstant(),
                        rs.getString("bearbeiter"), rs.getString("aktion"), rs.getString("vorher"), rs.getString("nachher"))).list();
        long gesamt = jdbc.sql("SELECT COUNT(*) FROM mitarbeiter_verlauf WHERE mitarbeiter_id = :id").param("id", id).query(Long.class).single();
        return new Seite(eintraege, gesamt);
    }
}
