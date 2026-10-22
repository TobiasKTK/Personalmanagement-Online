package de.pemonline.planung;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
@Component
public class PlanungSperre {
    private final JdbcClient jdbc;
    public PlanungSperre(JdbcClient jdbc) { this.jdbc = jdbc; }
    // Kalenderzuordnung und zentrale Änderungen nehmen diese Sperre vor Mitarbeitersperren.
    @Transactional(propagation = Propagation.MANDATORY)
    public void sperren() { jdbc.sql("SELECT id FROM planung_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single(); }
}
