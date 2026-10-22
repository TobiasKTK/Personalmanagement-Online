package de.pemonline.urlaub;

import de.pemonline.abwesenheit.Abwesenheit;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UrlaubRepository {
    public record Konfiguration(long mitarbeiterId, int jahr, double anspruch, double uebertrag, String arbeitstage, String feiertage, long version,
            Long feiertagskalenderId, String ausnahmen, String kalenderFeiertage, String kalenderName) {
        public Set<Integer> wochentage() { return Arrays.stream(arbeitstage.split(",")).map(Integer::valueOf).collect(Collectors.toSet()); }
        public Set<LocalDate> individuelleTage() { return daten(feiertage); }
        public Set<LocalDate> zentraleTage() { return daten(kalenderFeiertage); }
        public Set<LocalDate> ausnahmeTage() { return daten(ausnahmen); }
        public Set<LocalDate> freieTage() { var tage = new HashSet<>(zentraleTage()); tage.removeAll(ausnahmeTage()); tage.addAll(individuelleTage()); return tage; }
        private static Set<LocalDate> daten(String text) { return text.isEmpty() ? Set.of() : Arrays.stream(text.split(",")).map(LocalDate::parse).collect(Collectors.toSet()); }
    }
    private final JdbcClient jdbc;
    public UrlaubRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<Konfiguration> konto(long person, int jahr) {
        return jdbc.sql("SELECT k.*, COALESCE(f.feiertage, '') AS kalender_feiertage, COALESCE(f.name, '') AS kalender_name FROM urlaubskonto k LEFT JOIN feiertagskalender f ON f.id = k.feiertagskalender_id WHERE k.mitarbeiter_id = :id AND k.jahr = :jahr").param("id", person).param("jahr", jahr).query(Konfiguration.class).optional();
    }
    public List<Integer> jahre(long person) {
        return jdbc.sql("SELECT jahr FROM urlaubskonto WHERE mitarbeiter_id = :id ORDER BY jahr").param("id", person).query(Integer.class).list();
    }
    public boolean speichern(long person, int jahr, Urlaubskonto.Eingabe e) {
        String sql = e.version() == null
            ? "INSERT INTO urlaubskonto (mitarbeiter_id, jahr, anspruch, uebertrag, arbeitstage, feiertage, feiertagskalender_id, ausnahmen) VALUES (:id, :jahr, :anspruch, :uebertrag, :arbeitstage, :feiertage, :kalender, :ausnahmen)"
            : "UPDATE urlaubskonto SET anspruch = :anspruch, uebertrag = :uebertrag, arbeitstage = :arbeitstage, feiertage = :feiertage, feiertagskalender_id = :kalender, ausnahmen = :ausnahmen, version = version + 1 WHERE mitarbeiter_id = :id AND jahr = :jahr AND version = :version";
        var stmt = jdbc.sql(sql).param("id", person).param("jahr", jahr).param("anspruch", e.anspruch()).param("uebertrag", e.uebertrag())
            .param("arbeitstage", e.arbeitstage().stream().sorted().map(String::valueOf).collect(Collectors.joining(",")))
            .param("feiertage", e.feiertage().stream().sorted().map(date -> date.toString()).collect(Collectors.joining(",")))
            .param("kalender", e.feiertagskalenderId(), java.sql.Types.BIGINT)
            .param("ausnahmen", e.ausnahmen().stream().sorted().map(date -> date.toString()).collect(Collectors.joining(",")));
        if (e.version() != null) stmt = stmt.param("version", e.version());
        return stmt.update() == 1;
    }
    public List<Abwesenheit> urlaub(long person, int jahr) {
        return jdbc.sql("SELECT * FROM abwesenheit WHERE mitarbeiter_id = :id AND art = 'URLAUB' AND beginn <= :ende AND ende >= :beginn")
            .param("id", person).param("beginn", LocalDate.of(jahr, 1, 1)).param("ende", LocalDate.of(jahr, 12, 31)).query(Abwesenheit.class).list();
    }
    public boolean kalenderPasst(long id, int jahr) { return jdbc.sql("SELECT COUNT(*) FROM feiertagskalender WHERE id = :id AND jahr = :jahr").param("id", id).param("jahr", jahr).query(Long.class).single() == 1; }
}
