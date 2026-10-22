package de.pemonline.integration;

import java.time.Clock;
import java.util.UUID;
import de.pemonline.mandant.MandantenKontext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OutboxVersand {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxVersand.class);
    private final OutboxRepository repository;
    private final ExternerDienst dienst;
    private final Clock clock;
    private final JdbcClient verwaltung;
    public OutboxVersand(OutboxRepository repository, ExternerDienst dienst, Clock clock, @Qualifier("verwaltungJdbc") JdbcClient verwaltung) {
        this.repository = repository;
        this.dienst = dienst;
        this.clock = clock;
        this.verwaltung = verwaltung;
    }

    // Netzwerkzugriffe halten keine Datenbanktransaktion offen. Die Reservierung überlebt Abstürze nur 60 Sekunden.
    public void versenden() {
        if (dienst.adresse() == null) return;
        for (long id : verwaltung.sql("SELECT id FROM VERWALTUNG.mandantenspeicher WHERE status = 'AKTIV' ORDER BY id").query(Long.class).list()) {
            try (var kontext = MandantenKontext.intern(id)) {
                if (kontext == null) {
                    throw new IllegalStateException("MandantenKontext konnte nicht erstellt werden: " + id);
                }
                mandantVersenden();
            }
            catch (Exception e) { LOG.error("outbox_mandant_fehler mandantenspeicher_id={} typ={}", id, e.getClass().getSimpleName()); }
        }
    }
    private void mandantVersenden() {
        for (var auftrag : repository.faellig(clock.instant())) {
            UUID reservierung = UUID.randomUUID();
            if (!repository.reservieren(auftrag.id(), reservierung, clock.instant())) continue;
            if (dienst.senden(auftrag.ziel(), auftrag.ereignisId().toString(), auftrag.ereignis())) {
                repository.bestaetigen(auftrag.id(), reservierung);
            } else {
                long pause = Math.min(3600, 30L << Math.min(auftrag.versuche(), 7));
                repository.wiederholen(auftrag.id(), reservierung, clock.instant().plusSeconds(pause));
                LOG.warn("outbox_wiederholung ereignis={} versuch={} wartezeit_sekunden={}", auftrag.ereignisId(), auftrag.versuche() + 1, pause);
            }
        }
    }
}
