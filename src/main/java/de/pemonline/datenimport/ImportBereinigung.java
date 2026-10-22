package de.pemonline.datenimport;

import de.pemonline.mandant.MandantenKontext;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="pem.import.bereinigung-automatisch", havingValue="true", matchIfMissing=true)
public class ImportBereinigung {
    private static final Logger LOG = LoggerFactory.getLogger(ImportBereinigung.class);
    private final JdbcClient verwaltung;
    private final JdbcClient fach;
    private final Clock clock;
    public ImportBereinigung(@Qualifier("verwaltungJdbc") JdbcClient verwaltung, JdbcClient fach, Clock clock) {
        this.verwaltung = verwaltung; this.fach = fach; this.clock = clock;
    }
    @Scheduled(initialDelayString="${pem.import.bereinigung-start-ms:60000}", fixedDelayString="${pem.import.bereinigung-intervall-ms:60000}")
    public void bereinigen() {
        for (long id : verwaltung.sql("SELECT id FROM VERWALTUNG.mandantenspeicher WHERE status = 'AKTIV' ORDER BY id").query(Long.class).list()) {
            try (var kontext = MandantenKontext.intern(id)) {
                if (kontext == null) {
                    throw new IllegalStateException("MandantenKontext für id=" + id + " konnte nicht erzeugt werden");
                }
                // Keine firmenübergreifende Transaktion: die Verbindung wird erst im jeweiligen Kontext gewählt.
                fach.sql("DELETE FROM import_vorschau WHERE uebernommen = FALSE AND erstellt_am <= :ablauf")
                        .param("ablauf", clock.instant().minus(Duration.ofMinutes(30))).update();
            } catch (Exception e) {
                LOG.error("import_bereinigung_fehler mandantenspeicher_id={} typ={}", id, e.getClass().getSimpleName());
            }
        }
    }
}
