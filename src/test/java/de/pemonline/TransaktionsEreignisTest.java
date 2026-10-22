package de.pemonline;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import com.sun.net.httpserver.HttpServer;
import de.pemonline.mitarbeiter.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none", "mitarbeiter.outbox.automatisch=false", "spring.datasource.url=jdbc:h2:mem:ereignis-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "logging.file.name=target/test-logs/ereignisse.log"})
class TransaktionsEreignisTest {
    static final LinkedBlockingQueue<String> nachrichten = new LinkedBlockingQueue<>();
    static final LinkedBlockingQueue<String> kennungen = new LinkedBlockingQueue<>();
    static final HttpServer server = serverStarten();
    static volatile int antwortStatus = 204;
    @Autowired MitarbeiterService service;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transaktionen;
    @Autowired de.pemonline.integration.OutboxVersand versand;
    @Autowired de.pemonline.integration.OutboxRepository outbox;

    private static HttpServer serverStarten() {
        try {
            var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", exchange -> {
                kennungen.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                nachrichten.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(antwortStatus, -1);
                exchange.close();
            });
            stub.start();
            return stub;
        } catch (Exception fehler) {
            throw new IllegalStateException(fehler);
        }
    }

    @DynamicPropertySource
    static void eigenschaften(DynamicPropertyRegistry registry) {
        registry.add("mitarbeiter.externer-dienst-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @BeforeEach
    void vorbereiten() {
        TestSicherheit.anmelden("ADMIN");
        nachrichten.clear();
        kennungen.clear();
        antwortStatus = 204;
        jdbc.sql("DELETE FROM mitarbeiter").update();
        jdbc.sql("DELETE FROM ereignis_outbox").update();
    }

    @AfterEach void abmelden() { TestSicherheit.abmelden(); }

    @AfterAll
    static void stoppen() {
        server.stop(0);
    }

    @Test
    void rollbackVeroeffentlichtKeinEreignis() {
        new TransactionTemplate(transaktionen).executeWithoutResult(status -> {
            service.anlegen(eingabe());
            status.setRollbackOnly();
        });
        assertThat(nachrichten).isEmpty();
        assertThat(service.statistik().anzahl()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ereignis_outbox").query(Long.class).single()).isZero();
        versand.versenden();
        assertThat(nachrichten).isEmpty();
    }

    @Test
    void erfolgreicheAenderungenVeroeffentlichenEreignisse() {
        var person = service.anlegen(eingabe());
        var geaendert = service.aendern(person.id(), Testdaten.eingabe("Neuer Name", person.version()));
        service.loeschen(person.id(), geaendert.version());
        assertThat(nachrichten).isEmpty();
        for (int i = 0; i < 3; i++) versand.versenden();
        assertThat(nachrichten).hasSize(3);
        assertThat(nachrichten.poll()).contains("ANGELEGT");
        assertThat(nachrichten.poll()).contains("GEAENDERT");
        assertThat(nachrichten.poll()).contains("GELOESCHT");
    }

    @Test
    void externerAusfallMachtSpeicherungNichtRueckgaengig() {
        antwortStatus = 503;
        var person = service.anlegen(eingabe());
        assertThat(service.finden(person.id()).nachname()).isEqualTo("Testperson");
        versand.versenden();
        assertThat(nachrichten).hasSize(1);
        assertThat(jdbc.sql("SELECT versuche FROM ereignis_outbox").query(Integer.class).single()).isEqualTo(1);
        versand.versenden();
        assertThat(nachrichten).hasSize(1);
        antwortStatus = 204;
        jdbc.sql("UPDATE ereignis_outbox SET naechster_versuch = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'").update();
        versand.versenden();
        assertThat(nachrichten).hasSize(2);
        assertThat(kennungen).hasSize(2);
        assertThat(kennungen.poll()).isEqualTo(kennungen.poll());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ereignis_outbox").query(Long.class).single()).isZero();
    }

    private MitarbeiterEingabe eingabe() {
        return Testdaten.eingabe("Testperson", null);
    }

    @Test void stockenderEmpfaengerBlockiertAndereNachrichtenNichtUndWirdErneutVersucht() throws Exception {
        var freigabe = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var langsam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        langsam.setExecutor(executor);
        var empfangeneKennungen = new LinkedBlockingQueue<String>();
        langsam.createContext("/", exchange -> {
            empfangeneKennungen.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try { freigabe.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        langsam.start();
        try {
            var person = service.anlegen(eingabe());
            service.anlegen(eingabe());
            jdbc.sql("UPDATE ereignis_outbox SET ziel = :ziel WHERE mitarbeiter_id = :id")
                    .param("ziel", "http://127.0.0.1:" + langsam.getAddress().getPort())
                    .param("id", person.id()).update();
            Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), versand::versenden);
            assertThat(nachrichten).hasSize(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM ereignis_outbox").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT versuche FROM ereignis_outbox").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM ereignis_outbox WHERE reservierung IS NOT NULL").query(Long.class).single()).isZero();
            freigabe.countDown();
            langsam.removeContext("/");
            langsam.createContext("/", exchange -> {
                empfangeneKennungen.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            jdbc.sql("UPDATE ereignis_outbox SET naechster_versuch = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'").update();
            versand.versenden();
            assertThat(empfangeneKennungen).hasSize(2);
            assertThat(empfangeneKennungen.poll()).isEqualTo(empfangeneKennungen.poll());
            assertThat(jdbc.sql("SELECT COUNT(*) FROM ereignis_outbox").query(Long.class).single()).isZero();
        } finally {
            freigabe.countDown();
            langsam.stop(0);
            executor.shutdownNow();
        }
    }

    @Test void reservierungVerhindertDoppelversandUndWirdNachAbsturzFreigegeben() {
        service.anlegen(eingabe());
        var auftrag = outbox.faellig(java.time.Instant.now()).getFirst();
        var jetzt = java.time.Instant.now();
        assertThat(outbox.reservieren(auftrag.id(), java.util.UUID.randomUUID(), jetzt)).isTrue();
        assertThat(outbox.reservieren(auftrag.id(), java.util.UUID.randomUUID(), jetzt)).isFalse();
        versand.versenden();
        assertThat(nachrichten).isEmpty();
        jdbc.sql("UPDATE ereignis_outbox SET reserviert_bis = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00'").update();
        versand.versenden();
        assertThat(nachrichten).hasSize(1);
        assertThat(outbox.faellig(java.time.Instant.now())).isEmpty();
    }
}
