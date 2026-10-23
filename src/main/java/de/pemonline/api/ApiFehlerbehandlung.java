package de.pemonline.api;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiFehlerbehandlung {
    private static final Logger LOG = LoggerFactory.getLogger(ApiFehlerbehandlung.class);

    public record ApiFehler(int status, String meldung, Map<String, String> felder) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiFehler> validierung(MethodArgumentNotValidException fehler) {
        Map<String, String> felder = new LinkedHashMap<>();
        fehler.getBindingResult().getFieldErrors().forEach(
                feld -> felder.putIfAbsent(feld.getField(), feld.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiFehler(400, "Bitte die Eingaben prüfen.", felder));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiFehler> json() {
        return antwort(HttpStatus.BAD_REQUEST,
                "Ungültige JSON-Eingabe. Bitte Feldnamen, Zahlen und Datumsangaben (JJJJ-MM-TT) prüfen.");
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiFehler> dateiZuGross() {
        return antwort(HttpStatus.CONTENT_TOO_LARGE, "PDF-Dateien dürfen höchstens 5 MiB groß sein.");
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiFehler> parameter() {
        return antwort(HttpStatus.BAD_REQUEST, "Ungültige oder fehlende Anfrageparameter.");
    }

    @ExceptionHandler(de.pemonline.fachlich.FachlicherFehler.class)
    public ResponseEntity<ApiFehler> fachlich(de.pemonline.fachlich.FachlicherFehler fehler) {
        int status = switch (fehler.art()) {
            case UNGUELTIG -> 400;
            case NICHT_GEFUNDEN -> 404;
            case KONFLIKT -> 409;
        };
        return ResponseEntity.status(status).body(new ApiFehler(status, fehler.getMessage(), fehler.felder()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiFehler> status(ResponseStatusException fehler) {
        return ResponseEntity.status(fehler.getStatusCode()).body(
                new ApiFehler(fehler.getStatusCode().value(), fehler.getReason(), Map.of()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiFehler> doppelterEintrag() {
        return antwort(HttpStatus.CONFLICT, "Ein Eintrag mit diesen eindeutigen Angaben existiert bereits.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiFehler> datenbank(DataAccessException fehler) {
        // Keine SQL-Parameter oder Mitarbeiterdaten ins Protokoll schreiben.
        LOG.error("datenbank_fehler typ={}", fehler.getClass().getSimpleName());
        return antwort(HttpStatus.SERVICE_UNAVAILABLE, "Die Datenbank ist momentan nicht verfügbar.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiFehler> datenKonflikt() {
        return antwort(HttpStatus.CONFLICT, "Die Änderung widerspricht inzwischen gespeicherten Daten. Bitte neu laden und die Zuordnung prüfen.");
    }

    private ResponseEntity<ApiFehler> antwort(HttpStatus status, String meldung) {
        return ResponseEntity.status(status).body(new ApiFehler(status.value(), meldung, Map.of()));
    }
}
