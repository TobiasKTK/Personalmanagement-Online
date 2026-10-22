package de.pemonline.auswertung;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class AuswertungModel {
    private AuswertungModel() {}
    public record Seite<T>(List<T> eintraege, long gesamt, int seite, int groesse) {}
    public record Organisation(Long organisationId, String organisation, boolean historieBekannt,
            long beschaeftigte, BigDecimal wochenstunden, BigDecimal fte) {}
    public record Stelle(long id, String bezeichnung, long organisationId, String organisation,
            String profil, BigDecimal anteil) {}
    public record Bedarf(long mitarbeiterId, long personalnummer, String name, Long organisationId,
            String organisation, boolean historieBekannt, long kenntnisId, String kenntnis,
            String kategorie, int soll, int ist, int differenz, String grund) {}
    public record Uebersicht(LocalDate stichtag, Long organisationseinheitId,
            BigDecimal vollzeitWochenstunden, long beschaeftigte, BigDecimal wochenstunden, BigDecimal fte,
            long historieUnbekanntFirma, Seite<Organisation> organisationen, Seite<Stelle> offeneStellen,
            Seite<Bedarf> qualifikationsluecken, List<String> hinweise) {}
}
