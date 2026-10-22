package de.pemonline;

import de.pemonline.export.ExportService;
import de.pemonline.mitarbeiter.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "spring.main.web-application-type=none", "spring.datasource.url=jdbc:h2:mem:export-konsistenz;DB_CLOSE_DELAY=-1",
    "mitarbeiter.outbox.automatisch=false"})
class ExportKonsistenzTest {
    @Autowired ExportService export;
    @Autowired MitarbeiterService mitarbeiter;
    @MockitoSpyBean MitarbeiterRepository repository;

    @BeforeEach void anmelden() { TestSicherheit.anmelden("ADMIN"); }
    @AfterEach void abmelden() { TestSicherheit.abmelden(); }

    @Test void paralleleNeuanlageVerschiebtKeineZeilenZwischenExportseiten() throws Exception {
        var personalnummern = new ArrayList<String>();
        for (int i = 0; i < 101; i++) {
            var person = mitarbeiter.anlegen(Testdaten.eingabe("Person%03d".formatted(i), null));
            personalnummern.add("\"" + person.personalnummer() + "\"");
        }
        var ersteSeiteGelesen = new CountDownLatch(1);
        var weiter = new CountDownLatch(1);
        doAnswer(aufruf -> {
            var ergebnis = aufruf.callRealMethod();
            ersteSeiteGelesen.countDown();
            if (!weiter.await(10, TimeUnit.SECONDS)) throw new AssertionError("Parallele Neuanlage wurde nicht abgeschlossen.");
            return ergebnis;
        }).when(repository).suchen(any(MitarbeiterFilter.class), eq(0), eq(100));
        var lesen = CompletableFuture.supplyAsync(() -> {
            TestSicherheit.anmelden("ADMIN");
            try { return export.mitarbeiter(MitarbeiterFilter.standard()); }
            finally { TestSicherheit.abmelden(); }
        });
        try {
            assertThat(ersteSeiteGelesen.await(10, TimeUnit.SECONDS)).isTrue();
            mitarbeiter.anlegen(Testdaten.eingabe("AAAA Parallel", null));
        } finally { weiter.countDown(); }
        var csv = new String(lesen.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        assertThat(csv.lines().skip(1).map(zeile -> zeile.substring(0, zeile.indexOf(';'))).toList())
            .as("Jede Person aus dem Exportstand genau einmal ausgeben")
            .containsExactlyInAnyOrderElementsOf(personalnummern);
    }
}
