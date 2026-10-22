package de.pemonline.mitarbeiter;

import java.math.BigDecimal;

public record Statistik(long anzahl, BigDecimal wochenstunden, long bevorstehendeEintritte,
        long aktiv, long ausgeschieden, java.time.LocalDate stichtag, long historieUnbekannt) {
}
