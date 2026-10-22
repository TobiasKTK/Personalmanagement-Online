package de.pemonline.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mitarbeiter.outbox.automatisch", havingValue = "true", matchIfMissing = true)
public class OutboxZeitplan {
    private final OutboxVersand versand;
    public OutboxZeitplan(OutboxVersand versand) { this.versand = versand; }
    @Scheduled(fixedDelayString = "${mitarbeiter.outbox.intervall-ms:1000}", initialDelay = 1000)
    public void versenden() { versand.versenden(); }
}
