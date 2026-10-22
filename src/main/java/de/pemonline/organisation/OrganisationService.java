package de.pemonline.organisation;

import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.verlauf.VerlaufService;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;

@Service
@Validated
@PreAuthorize("hasAnyRole('LESEN', 'PERSONAL', 'ADMIN')")
@Transactional(readOnly = true)
public class OrganisationService {
    private final OrganisationRepository repository;
    private final VerlaufService verlauf;
    private final java.time.Clock clock;
    public OrganisationService(OrganisationRepository repository, VerlaufService verlauf, java.time.Clock clock) { this.repository = repository; this.verlauf = verlauf; this.clock = clock; }

    public List<OrganisationRepository.Revision> revisionen(@PositiveOrZero int seite) { return repository.revisionen(seite); }
    public List<Organisation> stand(@Positive long revision) {
        repository.revision(revision).orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Organisationsstand nicht gefunden."));
        return repository.stand(revision);
    }

    public List<Organisation> liste() {
        return repository.liste();
    }
    public Organisation finden(@Positive long id) {
        return repository.finden(id).orElseThrow(() -> new FachlicherFehler(NICHT_GEFUNDEN, "Organisationseinheit nicht gefunden."));
    }
    public Long zuordnung(Long id, String bisherigerName) {
        if (id != null) {
            if (id < 1) throw new FachlicherFehler(UNGUELTIG, "Ungültige Organisationseinheit.");
            finden(id);
            return id;
        }
        if (bisherigerName == null || bisherigerName.isBlank()) return null;
        return repository.finden(bisherigerName)
                .orElseThrow(() -> new FachlicherFehler(UNGUELTIG, "Bitte eine vorhandene Organisationseinheit auswählen."));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Organisation speichern(@Positive Long id, @Valid @NotNull OrganisationEingabe eingabe) {
        repository.sperren();
        if (id != null && eingabe.version() == null) throw new FachlicherFehler(UNGUELTIG, "Die Version fehlt.");
        Long parent = eingabe.parentId();
        var besucht = new java.util.HashSet<Long>();
        while (parent != null) {
            if (parent.equals(id) || !besucht.add(parent)) throw new FachlicherFehler(UNGUELTIG, "Eine Hierarchie darf keinen Kreis enthalten.");
            parent = finden(parent).parentId();
        }
        try {
            if (id == null) {
                var neu = finden(repository.anlegen(eingabe));
                repository.protokollieren(java.time.OffsetDateTime.now(clock), "Angelegt: " + neu.name());
                return neu;
            }
            Organisation vorher = finden(id);
            if (!repository.aendern(id, eingabe)) throw new FachlicherFehler(KONFLIKT, "Organisationseinheit wurde inzwischen geändert.");
            if (!vorher.name().equals(eingabe.name())) verlauf.organisationUmbenannt(id, vorher.name(), eingabe.name());
            repository.protokollieren(java.time.OffsetDateTime.now(clock), "Geändert: " + eingabe.name());
            return finden(id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new FachlicherFehler(KONFLIKT, "Eine Organisationseinheit mit diesem Namen existiert bereits.");
        }
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void loeschen(@Positive long id, @PositiveOrZero long version) {
        repository.sperren();
        var vorher = finden(id);
        try {
            if (!repository.loeschen(id, version))
                throw new FachlicherFehler(KONFLIKT, "Organisationseinheit wurde inzwischen geändert.");
            repository.protokollieren(java.time.OffsetDateTime.now(clock), "Gelöscht: " + vorher.name());
        } catch (DataIntegrityViolationException e) {
            throw new FachlicherFehler(KONFLIKT, "Diese Organisationseinheit wird noch verwendet (Mitarbeiter, Untereinheit, Planung oder Ausbildung).");
        }
    }
}
