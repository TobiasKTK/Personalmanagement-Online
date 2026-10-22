package de.pemonline.fristen;

import de.pemonline.mandant.MandantenKontext;
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
@ConditionalOnProperty(name="pem.fristen.automatisch", havingValue="true", matchIfMissing=true)
public class FristenZeitplan {
    private static final Logger LOG = LoggerFactory.getLogger(FristenZeitplan.class);
    private final JdbcClient verwaltung;
    private final FristenAbgleich abgleich;
    public FristenZeitplan(@Qualifier("verwaltungJdbc") JdbcClient verwaltung, FristenAbgleich abgleich) {
        this.verwaltung = verwaltung; this.abgleich = abgleich;
    }
    // Auch nach einem Neustart werden bereits fällige, noch nicht versandte Hinweise nachgeholt.
    @Scheduled(initialDelayString="${pem.fristen.start-ms:60000}", fixedDelayString="${pem.fristen.intervall-ms:3600000}")
    @SuppressWarnings("try")
    public void pruefen() {
        for (long id : verwaltung.sql("SELECT id FROM VERWALTUNG.mandantenspeicher WHERE status = 'AKTIV' ORDER BY id").query(Long.class).list()) {
            try (@SuppressWarnings("unused") var kontext = MandantenKontext.intern(id)) {
                // Die separate Bean beginnt ihre Transaktion erst nach der Kontextauswahl.
                abgleich.aktualisieren();
            } catch (Exception e) {
                LOG.error("fristen_abgleich_fehler mandantenspeicher_id={} typ={}", id, e.getClass().getSimpleName());
            }
        }
    }
}
