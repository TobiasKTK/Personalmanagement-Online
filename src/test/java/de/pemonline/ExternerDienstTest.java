package de.pemonline;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import de.pemonline.integration.*;
import de.pemonline.integration.MitarbeiterEreignis.Aktion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ExternerDienstTest {
    private HttpServer server;
    private ExecutorService executor;
    private final LinkedBlockingQueue<String> nachrichten = new LinkedBlockingQueue<>();
    private int status;

    @BeforeEach
    void starten() throws Exception {
        status = 204;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/ereignisse", exchange -> {
            nachrichten.add(exchange.getRequestMethod() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stoppen() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void sendetEreignisPerHttpOhnePersonendaten() throws Exception {
        var dienst = new ExternerDienst(url(), JsonMapper.builder().findAndAddModules().build());
        assertThat(dienst.senden(ereignis())).isTrue();
        assertThat(nachrichten.poll(1, TimeUnit.SECONDS)).startsWith("POST ")
                .contains("\"mitarbeiterId\":42", "\"aktion\":\"ANGELEGT\"", "zeitpunkt")
                .doesNotContain("name", "wochenstunden", "eintrittsdatum");
    }

    @Test
    void abgeschalteterDienstMachtKeineAnfragen() {
        assertThat(new ExternerDienst("", JsonMapper.builder().build()).senden(ereignis())).isFalse();
        assertThat(nachrichten).isEmpty();
    }

    @Test
    void httpFehlerWirdAbgefangen() {
        status = 503;
        assertThat(new ExternerDienst(url(), JsonMapper.builder().findAndAddModules().build()).senden(ereignis())).isFalse();
    }

    @Test
    void verbindungsfehlerWirdAbgefangen() {
        String adresse = url();
        server.stop(0);
        assertThat(new ExternerDienst(adresse, JsonMapper.builder().findAndAddModules().build()).senden(ereignis())).isFalse();
    }

    @Test
    void stockenderAntwortkoerperWirdAbgebrochenUndNaechsteAnfrageGelingt() {
        var freigabe = new CountDownLatch(1);
        var ersteAnfrage = new java.util.concurrent.atomic.AtomicBoolean(true);
        server.createContext("/stockend", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (!ersteAnfrage.getAndSet(false)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try { freigabe.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var dienst = new ExternerDienst(url().replace("/ereignisse", "/stockend"),
                JsonMapper.builder().findAndAddModules().build());
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(4),
                    () -> assertThat(dienst.senden(ereignis())).isFalse());
            assertThat(dienst.senden(ereignis())).isTrue();
        } finally { freigabe.countDown(); }
    }

    @Test
    void ungueltigeDienstUrlWirdBeimStartAbgewiesen() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ExternerDienst("file:///tmp/datei", JsonMapper.builder().build()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExternerDienst("https://user:secret@example.invalid/", JsonMapper.builder().build()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExternerDienst("https://example.invalid/#fragment", JsonMapper.builder().build()));
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/ereignisse";
    }

    private MitarbeiterEreignis ereignis() {
        return new MitarbeiterEreignis(42, Aktion.ANGELEGT, Instant.parse("2026-10-05T10:00:00Z"), 1);
    }
}
