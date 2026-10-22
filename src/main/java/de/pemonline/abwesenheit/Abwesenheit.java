package de.pemonline.abwesenheit;
import java.time.LocalDate;
public record Abwesenheit(long id, long mitarbeiterId, Abwesenheitsart art, LocalDate beginn, LocalDate ende, long version, boolean halberTag) {}
