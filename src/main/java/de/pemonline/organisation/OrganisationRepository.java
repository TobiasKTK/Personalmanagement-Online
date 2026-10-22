package de.pemonline.organisation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class OrganisationRepository {
    private final JdbcClient jdbc;
    public OrganisationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public List<Organisation> liste() {
        return jdbc.sql("SELECT id, name, version, parent_id FROM organisationseinheit ORDER BY name_normalisiert, id").query(Organisation.class).list();
    }
    public Optional<Organisation> finden(long id) {
        return jdbc.sql("SELECT id, name, version, parent_id FROM organisationseinheit WHERE id = :id").param("id", id).query(Organisation.class).optional();
    }
    public Optional<Long> finden(String name) {
        return jdbc.sql("SELECT id FROM organisationseinheit WHERE name_normalisiert = :name")
                .param("name", name.strip().toLowerCase(Locale.ROOT)).query(Long.class).optional();
    }
    public long anlegen(OrganisationEingabe e) {
        var key = new GeneratedKeyHolder();
        parameter(jdbc.sql("INSERT INTO organisationseinheit (name, name_normalisiert, parent_id) VALUES (:name, :normalisiert, :parent)"), e).update(key, "id");
        return key.getKey().longValue();
    }
    public boolean aendern(long id, OrganisationEingabe e) {
        return parameter(jdbc.sql("UPDATE organisationseinheit SET name = :name, name_normalisiert = :normalisiert, parent_id = :parent, version = version + 1 WHERE id = :id AND version = :version"), e)
                .param("id", id).param("version", e.version()).update() == 1;
    }
    public boolean loeschen(long id, long version) {
        return jdbc.sql("DELETE FROM organisationseinheit WHERE id = :id AND version = :version")
                .param("id", id).param("version", version).update() == 1;
    }
    private JdbcClient.StatementSpec parameter(JdbcClient.StatementSpec sql, OrganisationEingabe e) {
        return sql.param("name", e.name()).param("normalisiert", e.name().toLowerCase(Locale.ROOT)).param("parent", e.parentId(), java.sql.Types.BIGINT);
    }

    public void sperren() { jdbc.sql("SELECT id FROM organisation_sperre WHERE id = 1 FOR UPDATE").query(Integer.class).single(); }
    public record Revision(long id, java.time.OffsetDateTime zeitpunkt, String bearbeiter, String beschreibung) {}
    public List<Revision> revisionen(int seite) {
        return jdbc.sql("SELECT * FROM organisation_revision ORDER BY id DESC LIMIT 50 OFFSET :offset")
                .param("offset", (long) seite * 50).query(Revision.class).list();
    }
    public Optional<Revision> revision(long id) {
        return jdbc.sql("SELECT * FROM organisation_revision WHERE id = :id").param("id", id).query(Revision.class).optional();
    }
    public List<Organisation> stand(long revision) {
        return jdbc.sql("SELECT id, name, version, parent_id FROM organisation_stand WHERE revision_id = :revision ORDER BY LOWER(name), id")
                .param("revision", revision).query(Organisation.class).list();
    }
    public void protokollieren(java.time.OffsetDateTime zeitpunkt, String beschreibung) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO organisation_revision (zeitpunkt, bearbeiter, beschreibung) VALUES (:zeit, :name, :text)")
                .param("zeit", zeitpunkt).param("name", org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName())
                .param("text", beschreibung).update(key, "id");
        jdbc.sql("INSERT INTO organisation_stand (revision_id, id, name, parent_id, version) SELECT :revision, id, name, parent_id, version FROM organisationseinheit")
                .param("revision", key.getKey().longValue()).update();
    }
}
