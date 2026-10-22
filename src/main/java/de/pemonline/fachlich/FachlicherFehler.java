package de.pemonline.fachlich;

import java.util.Map;

/** Fachliche Fehler kennen weder HTTP noch die Oberfläche. */
public class FachlicherFehler extends RuntimeException {
    public enum Art { UNGUELTIG, NICHT_GEFUNDEN, KONFLIKT }
    private final Art art;
    private final Map<String, String> felder;
    public FachlicherFehler(Art art, String meldung) { this(art, meldung, Map.of()); }
    public FachlicherFehler(Art art, String meldung, Map<String, String> felder) {
        super(meldung);
        this.art = art;
        this.felder = Map.copyOf(felder);
    }
    public Art art() { return art; }
    public Map<String, String> felder() { return felder; }
}
