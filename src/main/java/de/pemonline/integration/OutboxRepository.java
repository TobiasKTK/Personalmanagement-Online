package de.pemonline.integration;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    public record Auftrag(long id, UUID ereignisId, long mitarbeiterId, String aktion, Instant zeitpunkt, String ziel, int versuche) {
        public MitarbeiterEreignis ereignis() {
            return new MitarbeiterEreignis(mitarbeiterId, MitarbeiterEreignis.Aktion.valueOf(aktion), zeitpunkt);
        }
    }
    private final JdbcClient jdbc;
    public OutboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public void anlegen(MitarbeiterEreignis e, String ziel) {
        jdbc.sql("INSERT INTO ereignis_outbox (ereignis_id, mitarbeiter_id, aktion, zeitpunkt, ziel, naechster_versuch) VALUES (:uuid, :person, :aktion, :zeit, :ziel, :zeit)")
                .param("uuid", UUID.randomUUID()).param("person", e.mitarbeiterId()).param("aktion", e.aktion().name())
                .param("zeit", zeit(e.zeitpunkt())).param("ziel", ziel).update();
    }
    public List<Auftrag> faellig(Instant jetzt) {
        // Ereignisse eines Mitarbeiters dürfen einander auch bei Wiederholungen nicht überholen.
        return jdbc.sql("""
                SELECT o.* FROM ereignis_outbox o
                WHERE naechster_versuch <= :jetzt AND (reserviert_bis IS NULL OR reserviert_bis <= :jetzt)
                AND NOT EXISTS (SELECT 1 FROM ereignis_outbox v WHERE v.mitarbeiter_id = o.mitarbeiter_id AND v.id < o.id)
                ORDER BY id LIMIT 20
                """).param("jetzt", zeit(jetzt))
                .query((rs, n) -> new Auftrag(rs.getLong("id"), rs.getObject("ereignis_id", UUID.class),
                        rs.getLong("mitarbeiter_id"), rs.getString("aktion"), rs.getTimestamp("zeitpunkt").toInstant(),
                        rs.getString("ziel"), rs.getInt("versuche"))).list();
    }
    public boolean reservieren(long id, UUID reservierung, Instant jetzt) {
        return jdbc.sql("UPDATE ereignis_outbox SET reservierung = :token, reserviert_bis = :bis WHERE id = :id AND naechster_versuch <= :jetzt AND (reserviert_bis IS NULL OR reserviert_bis <= :jetzt)")
                .param("id", id).param("token", reservierung).param("bis", zeit(jetzt.plusSeconds(60))).param("jetzt", zeit(jetzt)).update() == 1;
    }
    public void bestaetigen(long id, UUID token) {
        jdbc.sql("DELETE FROM ereignis_outbox WHERE id = :id AND reservierung = :token").param("id", id).param("token", token).update();
    }
    public void wiederholen(long id, UUID token, Instant naechster) {
        jdbc.sql("UPDATE ereignis_outbox SET versuche = versuche + 1, naechster_versuch = :zeit, reservierung = NULL, reserviert_bis = NULL WHERE id = :id AND reservierung = :token")
                .param("id", id).param("token", token).param("zeit", zeit(naechster)).update();
    }
    private OffsetDateTime zeit(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
