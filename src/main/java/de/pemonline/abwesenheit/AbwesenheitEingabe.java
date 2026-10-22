package de.pemonline.abwesenheit;
import java.time.LocalDate;
import jakarta.validation.constraints.*;
public record AbwesenheitEingabe(@NotNull Abwesenheitsart art, @NotNull LocalDate beginn, @NotNull LocalDate ende, @PositiveOrZero Long version, Boolean halberTag) {
    public AbwesenheitEingabe { halberTag = Boolean.TRUE.equals(halberTag); }
    public AbwesenheitEingabe(Abwesenheitsart art, LocalDate beginn, LocalDate ende, Long version) { this(art, beginn, ende, version, false); }
    @AssertTrue(message = "Ein halber Urlaubstag muss an einem einzelnen Datum liegen.")
    public boolean isHalberTagGueltig() { return !halberTag || (art == Abwesenheitsart.URLAUB && beginn != null && beginn.equals(ende)); }
}
