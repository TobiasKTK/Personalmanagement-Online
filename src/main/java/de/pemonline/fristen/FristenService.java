package de.pemonline.fristen;

import static de.pemonline.fristen.FristenModel.*;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.fachlich.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service @Validated @Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('PERSONAL', 'ADMIN')")
public class FristenService {
    private final JdbcClient jdbc;
    private final FristenAbgleich abgleich;
    private static final String SELECT = "SELECT f.*, m.name AS person FROM personalfrist f JOIN mitarbeiter m ON m.id = f.mitarbeiter_id WHERE f.aktiv = TRUE";
    public FristenService(JdbcClient jdbc, FristenAbgleich abgleich) { this.jdbc = jdbc; this.abgleich = abgleich; }
    public List<Frist> liste() { return jdbc.sql(SELECT + " ORDER BY f.erledigt, COALESCE(f.wiedervorlage, f.ablauf), f.id").query(Frist.class).list(); }
    public List<Zustaendig> zustaendige() {
        return jdbc.sql("""
            SELECT u.username AS name FROM users u JOIN authorities a ON a.username = u.username
            JOIN VERWALTUNG.konto k ON k.username = u.username WHERE u.enabled = TRUE AND k.enabled = TRUE
            AND a.authority IN ('ROLE_PERSONAL', 'ROLE_ADMIN') ORDER BY u.username
            """).query(Zustaendig.class).list();
    }
    @Transactional public void abgleichen() { abgleich.aktualisieren(); }
    @Transactional public Frist speichern(@Positive long id, @NotNull @Valid Eingabe e) {
        abgleich.synchronisieren();
        var alt = jdbc.sql(SELECT + " AND f.id = :id").param("id", id).query(Frist.class).optional()
            .orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Frist nicht gefunden oder Quelle entfernt."));
        if (alt.version() != e.version()) throw new FachlicherFehler(KONFLIKT, "Die Frist wurde inzwischen geändert. Bitte neu laden.");
        ZeitraumRegeln.kalenderjahr(e.wiedervorlage());
        String konto = e.zustaendig() == null || e.zustaendig().isBlank() ? null : e.zustaendig().strip();
        if (konto != null && zustaendige().stream().noneMatch(z -> z.name().equals(konto)))
            throw new FachlicherFehler(UNGUELTIG, "Bitte ein aktives Konto der Personalverwaltung oder Administration auswählen.");
        jdbc.sql("""
            UPDATE personalfrist SET zustaendig = :konto, vorlauf_tage = :vorlauf, wiedervorlage = :wieder,
            erledigt = :erledigt, version = version + 1 WHERE id = :id AND version = :version
            """).param("konto", konto).param("vorlauf", e.vorlaufTage()).param("wieder", e.wiedervorlage())
            .param("erledigt", e.erledigt()).param("id", id).param("version", e.version()).update();
        abgleich.erinnern();
        return jdbc.sql(SELECT + " AND f.id = :id").param("id", id).query(Frist.class).single();
    }
}
