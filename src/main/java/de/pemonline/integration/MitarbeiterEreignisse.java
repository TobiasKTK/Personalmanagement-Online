package de.pemonline.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MitarbeiterEreignisse {
    private static final Logger LOG = LoggerFactory.getLogger(MitarbeiterEreignisse.class);
    // Erst nach erfolgreichem Datenbank-Commit protokollieren. Der Versand erfolgt aus der Outbox.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void nachSpeichern(MitarbeiterEreignis ereignis) {
        LOG.info("mitarbeiter_ereignis aktion={} id={}", ereignis.aktion(), ereignis.mitarbeiterId());
    }
}
