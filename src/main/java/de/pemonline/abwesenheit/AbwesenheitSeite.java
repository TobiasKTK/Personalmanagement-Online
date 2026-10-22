package de.pemonline.abwesenheit;
import java.util.List;
public record AbwesenheitSeite(List<Abwesenheit> eintraege, long gesamt) {}
