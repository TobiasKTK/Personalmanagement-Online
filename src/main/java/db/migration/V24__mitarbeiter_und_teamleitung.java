package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** V4 besitzt einen von H2 vergebenen Constraint-Namen; je Firmenschema gezielt erweitern. */
public class V24__mitarbeiter_und_teamleitung extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 2401; }
    @Override public void migrate(Context context) throws Exception {
        var c = context.getConnection();
        var namen = new ArrayList<String>();
        try (var p = c.prepareStatement("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'AUTHORITIES' AND CONSTRAINT_TYPE = 'CHECK'")) {
            p.setString(1, c.getSchema());
            try (var r = p.executeQuery()) { while (r.next()) namen.add(r.getString(1)); }
        }
        if (namen.size() != 1) throw new IllegalStateException("Erwartet wird genau die Rollenbeschränkung aus V4.");
        try (var s = c.createStatement()) {
            s.execute("ALTER TABLE authorities DROP CONSTRAINT \"" + namen.getFirst().replace("\"", "\"\"") + "\"");
            s.execute("ALTER TABLE authorities ADD CONSTRAINT erlaubte_firmenrolle CHECK (authority IN ('ROLE_LESEN','ROLE_PERSONAL','ROLE_ADMIN','ROLE_MITARBEITER','ROLE_TEAMLEITUNG'))");
            s.execute("CREATE TABLE teamleitung_stand (konto VARCHAR(80) PRIMARY KEY REFERENCES users(username) ON DELETE CASCADE, version BIGINT NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE teamleitung_organisation (konto VARCHAR(80) NOT NULL REFERENCES teamleitung_stand(konto) ON DELETE CASCADE, organisation_id BIGINT NOT NULL REFERENCES organisationseinheit(id), PRIMARY KEY (konto, organisation_id))");
        }
    }
}
