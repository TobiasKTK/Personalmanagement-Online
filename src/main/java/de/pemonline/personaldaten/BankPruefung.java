package de.pemonline.personaldaten;

import de.pemonline.fachlich.FachlicherFehler;
import static de.pemonline.fachlich.FachlicherFehler.Art.UNGUELTIG;

/** Dieselben fachlichen Regeln für Personalverwaltung und eingereichte Bankänderungen. */
public final class BankPruefung {
    private BankPruefung() {}
    public static void pruefen(PersonaldatenModel.Bank bank) {
        String iban = bank.iban();
        if (iban.isEmpty()) return;
        if (bank.kontoinhaber().isBlank() || !iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}") || (iban.startsWith("DE") && iban.length() != 22))
            throw new FachlicherFehler(UNGUELTIG, "Bitte Kontoinhaber und eine gültige IBAN eingeben.");
        int rest = 0;
        for (char c : (iban.substring(4) + iban.substring(0, 4)).toCharArray()) {
            int wert = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            rest = (rest * (wert >= 10 ? 100 : 10) + wert) % 97;
        }
        if (rest != 1) throw new FachlicherFehler(UNGUELTIG, "Die IBAN-Prüfsumme ist ungültig.");
    }
}
