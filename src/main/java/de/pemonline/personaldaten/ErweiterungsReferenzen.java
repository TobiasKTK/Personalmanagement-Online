package de.pemonline.personaldaten;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Ergänzt alte JSON-Listen verlustfrei und dauerhaft um Datensatzreferenzen. */
@Service
public class ErweiterungsReferenzen {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public ErweiterungsReferenzen(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    @Transactional
    public void ergaenzen(long person) {
        var gespeichert = jdbc.sql("SELECT daten FROM personaldaten_erweiterung WHERE mitarbeiter_id = :id FOR UPDATE")
            .param("id", person).query(String.class).optional();
        if (gespeichert.isEmpty()) return;
        var daten = (ObjectNode) json.readTree(gespeichert.get());
        boolean geaendert = false;
        for (String liste : List.of("vollmachten", "beauftragungen", "fortbildungen")) {
            for (var eintrag : daten.path(liste)) if (eintrag.path("id").isMissingNode() || eintrag.path("id").isNull()) {
                ((ObjectNode)eintrag).put("id", UUID.randomUUID().toString()); geaendert = true;
            }
        }
        if (geaendert) {
            daten.put("version", daten.path("version").asLong() + 1);
            jdbc.sql("UPDATE personaldaten_erweiterung SET daten = :daten, version = version + 1 WHERE mitarbeiter_id = :id")
                .param("id", person).param("daten", json.writeValueAsString(daten)).update();
        }
    }
}
