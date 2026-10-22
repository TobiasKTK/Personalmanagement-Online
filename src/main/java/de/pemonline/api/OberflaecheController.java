package de.pemonline.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OberflaecheController {
    // Ausschließlich bekannte Oberflächenpfade weiterleiten; API-Fehler bleiben API-Fehler.
    @GetMapping({"/mitarbeiter", "/organisation", "/stellen", "/kenntnisse", "/ausbildung", "/kalender", "/urlaub", "/benachrichtigungen", "/dashboard", "/exporte", "/mandanten", "/administration/mandanten", "/administration/organisationen", "/administration/konten", "/administration/planung"})
    public String oberflaeche() { return "forward:/index.html"; }
}
