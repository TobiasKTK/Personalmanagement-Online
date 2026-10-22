package de.pemonline.abwesenheit;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AbwesenheitRepository {
    private final JdbcClient jdbc;
    public AbwesenheitRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public List<Abwesenheit> liste(long person, int seite, int groesse) {
        return jdbc.sql("SELECT * FROM abwesenheit WHERE mitarbeiter_id = :person ORDER BY beginn DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("person", person).param("limit", groesse).param("offset", (long) seite * groesse).query(Abwesenheit.class).list();
    }
    public long anzahl(long person) {
        return jdbc.sql("SELECT COUNT(*) FROM abwesenheit WHERE mitarbeiter_id = :person").param("person", person).query(Long.class).single();
    }
    public Optional<Abwesenheit> finden(long person, long id) {
        return jdbc.sql("SELECT * FROM abwesenheit WHERE id = :id AND mitarbeiter_id = :person")
                .param("id", id).param("person", person).query(Abwesenheit.class).optional();
    }
    public boolean ueberschneidung(long person, Long id, LocalDate beginn, LocalDate ende) {
        return jdbc.sql("SELECT COUNT(*) FROM abwesenheit WHERE mitarbeiter_id = :person AND beginn <= :ende AND ende >= :beginn AND id <> :id")
                .param("person", person).param("beginn", beginn).param("ende", ende).param("id", id == null ? -1 : id).query(Long.class).single() > 0;
    }
    public long anlegen(long person, AbwesenheitEingabe e) {
        var key = new GeneratedKeyHolder();
        parameter(jdbc.sql("INSERT INTO abwesenheit (mitarbeiter_id, art, beginn, ende, halber_tag) VALUES (:person, :art, :beginn, :ende, :halberTag)"), e)
                .param("person", person).update(key, "id");
        return key.getKey().longValue();
    }
    public boolean aendern(long person, long id, AbwesenheitEingabe e) {
        return parameter(jdbc.sql("UPDATE abwesenheit SET art = :art, beginn = :beginn, ende = :ende, halber_tag = :halberTag, version = version + 1 WHERE mitarbeiter_id = :person AND id = :id AND version = :version"), e)
                .param("person", person).param("id", id).param("version", e.version()).update() == 1;
    }
    public boolean loeschen(long person, long id, long version) {
        return jdbc.sql("DELETE FROM abwesenheit WHERE mitarbeiter_id = :person AND id = :id AND version = :version")
                .param("person", person).param("id", id).param("version", version).update() == 1;
    }
    public boolean ausserhalb(long id, LocalDate beginn, LocalDate ende) {
        return jdbc.sql("SELECT COUNT(*) FROM (SELECT mitarbeiter_id, beginn, ende FROM abwesenheit UNION ALL SELECT mitarbeiter_id, beginn, ende FROM urlaubsantrag WHERE status = 'BEANTRAGT') a WHERE mitarbeiter_id = :id AND (beginn < :beginn OR (:ende IS NOT NULL AND ende > :ende)) AND NOT EXISTS (SELECT 1 FROM beschaeftigungszeit b WHERE b.mitarbeiter_id = a.mitarbeiter_id AND a.beginn >= b.beginn AND a.ende <= b.ende)")
                .param("id", id).param("beginn", beginn).param("ende", ende, Types.DATE).query(Long.class).single() > 0;
    }
    private JdbcClient.StatementSpec parameter(JdbcClient.StatementSpec sql, AbwesenheitEingabe e) {
        return sql.param("art", e.art().name()).param("beginn", e.beginn()).param("ende", e.ende()).param("halberTag", e.halberTag());
    }
}
