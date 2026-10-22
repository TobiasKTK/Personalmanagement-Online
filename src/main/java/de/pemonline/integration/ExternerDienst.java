package de.pemonline.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExternerDienst {
    private static final Logger LOG = LoggerFactory.getLogger(ExternerDienst.class);
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public ExternerDienst(@Value("${mitarbeiter.externer-dienst-url:}") String url, ObjectMapper json) {
        this.json = json;
        this.endpoint = url.isBlank() ? null : URI.create(url);
        if (endpoint != null && (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || url.length() > 2048)) {
            throw new IllegalArgumentException("Die Dienst-URL muss eine vollständige HTTP- oder HTTPS-Adresse sein.");
        }
    }

    public boolean senden(MitarbeiterEreignis ereignis) {
        if (endpoint == null) {
            return false;
        }
        return senden(endpoint.toString(), java.util.UUID.randomUUID().toString(), ereignis);
    }

    public String adresse() { return endpoint == null ? null : endpoint.toString(); }

    boolean senden(String ziel, String ereignisId, MitarbeiterEreignis ereignis) {
        CompletableFuture<HttpResponse<Void>> versand = null;
        try {
            HttpRequest anfrage = HttpRequest.newBuilder(URI.create(ziel))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", ereignisId)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(ereignis))).build();
            versand = http.sendAsync(anfrage, HttpResponse.BodyHandlers.discarding());
            // Das Request-Timeout allein begrenzt das Lesen eines stockenden Antwortkörpers nicht.
            int status = versand.get(2, TimeUnit.SECONDS).statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            LOG.warn("externer_dienst_fehler id={} http_status={}", ereignis.mitarbeiterId(), status);
        } catch (InterruptedException fehler) {
            Thread.currentThread().interrupt();
            LOG.warn("externer_dienst_unterbrochen id={}", ereignis.mitarbeiterId());
        } catch (ExecutionException fehler) {
            LOG.warn("externer_dienst_nicht_erreichbar id={} typ={}",
                    ereignis.mitarbeiterId(), fehler.getCause().getClass().getSimpleName());
        } catch (TimeoutException | RuntimeException fehler) {
            LOG.warn("externer_dienst_nicht_erreichbar id={} typ={}",
                    ereignis.mitarbeiterId(), fehler.getClass().getSimpleName());
        } finally {
            if (versand != null && !versand.isDone()) versand.cancel(true);
        }
        return false;
    }
}
