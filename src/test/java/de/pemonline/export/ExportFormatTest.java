package de.pemonline.export;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExportFormatTest {
    @Test void csvMaskiertTrennzeichenZeilenumbruecheUndFormelpraefixe() {
        assertThat(ExportService.zelle("A;\"B\"\r\nC")).isEqualTo("\"A;\"\"B\"\"\r\nC\"");
        for (String s : java.util.List.of("=SUM(A1)", "   +12", "-1+2", "@Befehl", "\tText", "\rText")) assertThat(ExportService.zelle(s)).startsWith("\"'");
        assertThat(ExportService.zelle("2026-10-21")).isEqualTo("\"2026-10-21\""); assertThat(ExportService.zelle(null)).isEqualTo("\"\"");
    }
    @Test void kalenderMaskiertTextUndFaltetUnicodeOhneZeichenverlust() {
        String eingabe = "Müller; Text, \\ \r\nNoch eine Zeile" + "😀Ö".repeat(60);
        String escaped = ExportService.icsText(eingabe); assertThat(escaped).startsWith("Müller\\; Text\\, \\\\ \\nNoch eine Zeile");
        var out = new StringBuilder(); ExportService.icsZeile(out, "SUMMARY:" + escaped);
        for (String line : out.toString().split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        assertThat(out.toString().replace("\r\n ", "")).isEqualTo("SUMMARY:" + escaped + "\r\n");
    }
}
