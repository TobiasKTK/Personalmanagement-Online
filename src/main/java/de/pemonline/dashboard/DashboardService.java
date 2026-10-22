package de.pemonline.dashboard;

import de.pemonline.urlaub.*;
import java.time.*;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
public class DashboardService {
    public record Abwesend(long id, String name, String art, boolean halberTag) {}
    public record Termin(long id, String name, LocalDate datum) {}
    public record Uebersicht(LocalDate heute, long abwesendGesamt, List<Abwesend> abwesend,
        List<Termin> eintritte, List<Termin> austritte, Long offeneFreigaben, long eigeneOffene, Urlaubskonto urlaubskonto) {}
    private final JdbcClient jdbc;
    private final Clock clock;
    private final UrlaubsantragService antraege;
    private final UrlaubskontoService urlaub;
    public DashboardService(JdbcClient jdbc, Clock clock, UrlaubsantragService antraege, UrlaubskontoService urlaub) {
        this.jdbc = jdbc; this.clock = clock; this.antraege = antraege; this.urlaub = urlaub;
    }
    public Uebersicht laden() {
        var heute = LocalDate.now(clock);
        String from = " FROM abwesenheit a JOIN mitarbeiter m ON m.id = a.mitarbeiter_id WHERE a.beginn <= :heute AND a.ende >= :heute AND ((m.eintrittsdatum <= :heute AND (m.austrittsdatum IS NULL OR m.austrittsdatum >= :heute)) OR EXISTS (SELECT 1 FROM beschaeftigungszeit b WHERE b.mitarbeiter_id = m.id AND b.beginn <= :heute AND b.ende >= :heute))";
        var abwesend = jdbc.sql("SELECT m.id, m.name, a.art, a.halber_tag" + from + " ORDER BY LOWER(m.nachname), m.id LIMIT 10").param("heute", heute).query(Abwesend.class).list();
        long anzahl = jdbc.sql("SELECT COUNT(*)" + from).param("heute", heute).query(Long.class).single();
        boolean personal = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PERSONAL") || a.getAuthority().equals("ROLE_ADMIN"));
        var profil = antraege.profil().zuordnung();
        return new Uebersicht(heute, anzahl, abwesend, termine("eintrittsdatum", heute), termine("austrittsdatum", heute),
            personal ? antraege.liste(true, "BEANTRAGT", 0).gesamt() : null, antraege.liste(false, "BEANTRAGT", 0).gesamt(),
            profil == null ? null : urlaub.konto(profil.mitarbeiterId(), heute.getYear()));
    }
    private List<Termin> termine(String spalte, LocalDate heute) {
        // Spaltennamen ausschließlich aus den beiden festen Aufrufen oben.
        return jdbc.sql("SELECT id, name, " + spalte + " AS datum FROM mitarbeiter WHERE " + spalte + " BETWEEN :heute AND :bis ORDER BY " + spalte + ", id LIMIT 10")
            .param("heute", heute).param("bis", heute.plusDays(30)).query(Termin.class).list();
    }
}
