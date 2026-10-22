package de.pemonline.integration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private final OutboxRepository repository;
    private final ExternerDienst dienst;
    private final ApplicationEventPublisher ereignisse;
    public OutboxService(OutboxRepository repository, ExternerDienst dienst, ApplicationEventPublisher ereignisse) {
        this.repository = repository;
        this.dienst = dienst;
        this.ereignisse = ereignisse;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void vormerken(MitarbeiterEreignis e) {
        if (dienst.adresse() != null) repository.anlegen(e, dienst.adresse());
        ereignisse.publishEvent(e);
    }
}
