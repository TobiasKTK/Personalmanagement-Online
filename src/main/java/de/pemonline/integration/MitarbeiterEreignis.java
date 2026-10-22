package de.pemonline.integration;

import java.time.Instant;

public record MitarbeiterEreignis(long mitarbeiterId, Aktion aktion, Instant zeitpunkt, long mandantenspeicherId) {
    public MitarbeiterEreignis(long mitarbeiterId, Aktion aktion, Instant zeitpunkt) {
        this(mitarbeiterId, aktion, zeitpunkt, de.pemonline.mandant.MandantenKontext.id());
    }
    public enum Aktion { ANGELEGT, GEAENDERT, GELOESCHT }
}
