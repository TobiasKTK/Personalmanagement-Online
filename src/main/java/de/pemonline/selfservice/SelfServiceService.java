package de.pemonline.selfservice;

import static de.pemonline.selfservice.SelfServiceModel.*;
import static de.pemonline.fachlich.FachlicherFehler.Art.*;
import de.pemonline.fachlich.FachlicherFehler;
import de.pemonline.mitarbeiter.MitarbeiterRepository;
import de.pemonline.personaldaten.*;
import de.pemonline.personaldaten.PersonaldatenModel.*;
import de.pemonline.benachrichtigung.PersonalhinweisService;
import de.pemonline.urlaub.UrlaubsantragRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

@Service
@Validated
@Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('LESEN','PERSONAL','ADMIN','MITARBEITER','TEAMLEITUNG')")
public class SelfServiceService {
    private final JdbcClient jdbc;
    private final MitarbeiterRepository mitarbeiter;
    private final UrlaubsantragRepository zuordnungen;
    private final PersonaldatenService personaldaten;
    private final PersonalhinweisService hinweise;
    private final ObjectMapper json;
    private final Clock clock;
    private static final String SELECT = "SELECT a.*, m.name FROM selfservice_antrag a JOIN mitarbeiter m ON m.id=a.mitarbeiter_id ";
    public SelfServiceService(JdbcClient jdbc, MitarbeiterRepository mitarbeiter, UrlaubsantragRepository zuordnungen,
            PersonaldatenService personaldaten, PersonalhinweisService hinweise, ObjectMapper json, Clock clock) {
        this.jdbc=jdbc; this.mitarbeiter=mitarbeiter; this.zuordnungen=zuordnungen; this.personaldaten=personaldaten;
        this.hinweise=hinweise; this.json=json; this.clock=clock;
    }
    @Transactional
    public Profil profil() { return profil(eigenePersonGesperrt()); }
    private Profil profil(long person) {
        var m=mitarbeiter.finden(person,LocalDate.now(clock)).orElseThrow(SelfServiceService::fehlt);
        var kontakt=jdbc.sql("SELECT telefon,email,version FROM selfservice_kontakt WHERE mitarbeiter_id=:id").param("id",person)
            .query((rs,n)->new KontaktStand(new Kontakt(rs.getString("telefon"),rs.getString("email")),rs.getLong("version"))).optional().orElse(new KontaktStand(new Kontakt("",""),0));
        var d=bankDaten(person);
        return new Profil(person,m.name(),kontakt.kontakt(),kontakt.version(),d.bank(),d.version());
    }
    private record KontaktStand(Kontakt kontakt,long version) {}
    private Daten bankDaten(long person) {
        return jdbc.sql("SELECT daten FROM personaldaten WHERE mitarbeiter_id=:id").param("id",person).query(String.class).optional().map(s->json.readValue(s,Daten.class)).orElseGet(Daten::leer);
    }
    @Transactional
    public Seite eigeneAntraege(@Min(0) int seite) {
        long person=eigenePersonGesperrt();
        var liste=jdbc.sql(SELECT+"WHERE a.mitarbeiter_id=:person AND a.antragsteller=:konto ORDER BY a.id DESC LIMIT 20 OFFSET :offset")
            .param("person",person).param("konto",konto()).param("offset",(long)seite*20).query(this::antrag).list();
        long gesamt=jdbc.sql("SELECT COUNT(*) FROM selfservice_antrag WHERE mitarbeiter_id=:person AND antragsteller=:konto").param("person",person).param("konto",konto()).query(Long.class).single();
        return new Seite(liste,gesamt);
    }
    @PreAuthorize("hasAnyRole('PERSONAL','ADMIN')")
    public Seite pruefung(@Min(0) int seite) {
        return new Seite(jdbc.sql(SELECT+"ORDER BY CASE WHEN a.status='BEANTRAGT' THEN 0 ELSE 1 END, a.id DESC LIMIT 20 OFFSET :offset")
            .param("offset",(long)seite*20).query(this::antrag).list(),jdbc.sql("SELECT COUNT(*) FROM selfservice_antrag").query(Long.class).single());
    }
    public Antrag einzelner(@Positive long id) {
        var a=finden(id);
        if (!personal() && (!a.antragsteller().equals(konto()) || !istEigenePerson(a.mitarbeiterId()))) throw new AccessDeniedException("Keine Berechtigung für diesen Antrag.");
        return a;
    }
    @Transactional
    public Antrag einreichen(@NotNull @Valid Eingabe e) {
        long person=eigenePersonGesperrt();
        // Die ID ist ausschließlich ein Abgleich mit dem geladenen Formular, keine Auswahlberechtigung.
        if (e.mitarbeiterId()!=person) throw zuordnungGeaendert();
        var p=profil(person);
        long version=e.art()==Art.KONTAKT ? p.kontaktVersion() : p.bankVersion();
        if (e.bestandsversion()!=version) throw konflikt();
        Object vorher, nachher;
        if (e.art()==Art.KONTAKT) {
            if (e.kontakt()==null || e.bank()!=null) throw ungueltig("Bitte ausschließlich die Kontaktdaten angeben.");
            vorher=p.kontakt(); nachher=e.kontakt();
        } else {
            if (e.bank()==null || e.kontakt()!=null) throw ungueltig("Bitte ausschließlich die Bankverbindung angeben.");
            BankPruefung.pruefen(e.bank()); vorher=p.bank(); nachher=e.bank();
        }
        if (vorher.equals(nachher)) throw ungueltig("Die eingegebenen Daten entsprechen bereits dem gültigen Bestand.");
        if (jdbc.sql("SELECT COUNT(*) FROM selfservice_antrag WHERE mitarbeiter_id=:person AND art=:art AND status='BEANTRAGT'")
            .param("person",person).param("art",e.art().name()).query(Long.class).single()>0) throw new FachlicherFehler(KONFLIKT,"Zu diesen Daten liegt bereits ein offener Antrag vor.");
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO selfservice_antrag (mitarbeiter_id,antragsteller,art,vorher,nachher,bestandsversion,erstellt) VALUES (:person,:konto,:art,:vorher,:nachher,:version,:zeit)")
            .param("person",person).param("konto",konto()).param("art",e.art().name()).param("vorher",json.writeValueAsString(vorher)).param("nachher",json.writeValueAsString(nachher))
            .param("version",version).param("zeit",clock.instant()).update(key,"id");
        long id=key.getKey().longValue();
        for (String empfaenger : jdbc.sql("SELECT DISTINCT u.username FROM users u JOIN authorities r ON r.username=u.username JOIN VERWALTUNG.konto k ON k.username=u.username WHERE u.enabled=TRUE AND k.enabled=TRUE AND r.authority IN ('ROLE_PERSONAL','ROLE_ADMIN') AND u.username<>:konto").param("konto",konto()).query(String.class).list())
            hinweise.senden(empfaenger,"SELF_SERVICE_NEU_"+id,"Neue Datenänderung zur Prüfung.","/self-service/pruefung?antrag="+id);
        return finden(id);
    }
    @Transactional
    @PreAuthorize("hasAnyRole('PERSONAL','ADMIN')")
    public Antrag entscheiden(@Positive long id,@NotNull @Valid Entscheidung e) {
        var zuerst=finden(id);
        // Alle Personaldaten-Schreibwege sperren zuerst die Mitarbeiterzeile.
        sperren(zuerst.mitarbeiterId());
        var a=finden(id);
        if (!a.entscheidbar()) throw new AccessDeniedException("Eigene Anträge dürfen nicht selbst entschieden werden.");
        if (a.version()!=e.version() || a.status()!=Status.BEANTRAGT) throw konflikt();
        if (e.status()==Status.BEANTRAGT) throw ungueltig("Bitte genehmigen oder ablehnen.");
        if (e.status()==Status.ABGELEHNT && e.grund().isBlank()) throw ungueltig("Bitte die Ablehnung begründen.");
        if (e.status()==Status.GENEHMIGT) {
            var p=profil(a.mitarbeiterId());
            if (a.bestandsversion()!=(a.art()==Art.KONTAKT ? p.kontaktVersion() : p.bankVersion())) throw konflikt();
            if (a.art()==Art.KONTAKT) {
                var kontakt=json.convertValue(a.nachher(),Kontakt.class);
                jdbc.sql("MERGE INTO selfservice_kontakt (mitarbeiter_id,telefon,email,version) KEY(mitarbeiter_id) VALUES (:person,:telefon,:email,:version)")
                    .param("person",a.mitarbeiterId()).param("telefon",kontakt.telefon()).param("email",kontakt.email()).param("version",p.kontaktVersion()+1).update();
            } else {
                var d=bankDaten(a.mitarbeiterId()); var bank=json.convertValue(a.nachher(),Bank.class);
                personaldaten.speichern(a.mitarbeiterId(),new Daten(bank,d.profilId(),d.disziplinarisch(),d.fachlich(),d.kostenstellen(),d.vertretungen(),d.schwerbehinderung(),d.austrittsgrund(),d.kuendigungsdatum(),d.verguetungen(),d.version()));
            }
        }
        jdbc.sql("UPDATE selfservice_antrag SET status=:status,grund=:grund,bearbeiter=:konto,entschieden=:zeit,version=version+1 WHERE id=:id")
            .param("status",e.status().name()).param("grund",e.grund()).param("konto",konto()).param("zeit",clock.instant()).param("id",id).update();
        // Ein neu zugeordnetes Konto erhält keine Hinweise auf frühere fremde Anträge.
        if (zuordnungen.zuordnung(a.mitarbeiterId()).filter(z->a.antragsteller().equals(z.konto())).isPresent())
            hinweise.senden(a.antragsteller(),"SELF_SERVICE_ENTSCHIEDEN_"+id,"Ihre Datenänderung wurde "+(e.status()==Status.GENEHMIGT ? "genehmigt." : "abgelehnt."),"/self-service?antrag="+id);
        return finden(id);
    }
    @Transactional
    public PersonaldokumentService.Seite dokumente(@Min(0) int seite) {
        long person=eigenePersonGesperrt();
        String where=" FROM personaldokument WHERE mitarbeiter_id=:person AND art='ABRECHNUNG' AND selfservice_freigegeben=TRUE";
        var liste=jdbc.sql("SELECT id,titel,art,quelle,monat,dateiname,groesse,erstellt_am,selfservice_freigegeben,version,COALESCE(familie_id,id) AS familie_id,dokument_version"+where+" ORDER BY monat DESC,id DESC LIMIT 20 OFFSET :offset")
            .param("person",person).param("offset",(long)seite*20).query(PersonaldokumentService.Dokument.class).list();
        return new PersonaldokumentService.Seite(liste,jdbc.sql("SELECT COUNT(*)"+where).param("person",person).query(Long.class).single());
    }
    public byte[] dokument(@Positive long id) {
        return jdbc.sql("SELECT inhalt FROM personaldokument WHERE id=:id AND mitarbeiter_id=:person AND art='ABRECHNUNG' AND selfservice_freigegeben=TRUE AND EXISTS (SELECT 1 FROM urlaub_zuordnung z WHERE z.mitarbeiter_id=personaldokument.mitarbeiter_id AND z.konto=:konto)")
            .param("id",id).param("person",eigenePerson()).param("konto",konto()).query((rs,n)->rs.getBytes("inhalt")).optional().orElseThrow(SelfServiceService::fehlt);
    }
    private Antrag finden(long id) { return jdbc.sql(SELECT+"WHERE a.id=:id").param("id",id).query(this::antrag).optional().orElseThrow(SelfServiceService::fehlt); }
    private Antrag antrag(ResultSet rs,int zeile) throws SQLException {
        var entschieden=rs.getObject("entschieden",OffsetDateTime.class);
        long person=rs.getLong("mitarbeiter_id"); String antragsteller=rs.getString("antragsteller");
        return new Antrag(rs.getLong("id"),person,rs.getString("name"),antragsteller,Art.valueOf(rs.getString("art")),json.readTree(rs.getString("vorher")),json.readTree(rs.getString("nachher")),rs.getLong("bestandsversion"),Status.valueOf(rs.getString("status")),rs.getString("grund"),rs.getObject("erstellt",OffsetDateTime.class).toInstant(),entschieden==null ? null : entschieden.toInstant(),rs.getString("bearbeiter"),rs.getLong("version"),personal()&&!antragsteller.equals(konto())&&!istEigenePerson(person));
    }
    private long eigenePerson() { return zuordnungen.eigenesProfil(konto()).orElseThrow(()->new FachlicherFehler(KONFLIKT,"Ihr Konto ist noch keinem Mitarbeiter zugeordnet. Bitte wenden Sie sich an die Administration.")).mitarbeiterId(); }
    private long eigenePersonGesperrt() {
        long person=eigenePerson();
        // Zuordnungsänderungen sperren ebenfalls die Mitarbeiterzeile; bis Transaktionsende bleibt sie stabil.
        sperren(person);
        if (!istEigenePerson(person)) throw zuordnungGeaendert();
        return person;
    }
    private boolean istEigenePerson(long person) { return zuordnungen.eigenesProfil(konto()).filter(z->z.mitarbeiterId()==person).isPresent(); }
    private void sperren(long person) { if (!mitarbeiter.sperren(person)) throw fehlt(); }
    private static String konto() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    private static boolean personal() { return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a->Set.of("ROLE_PERSONAL","ROLE_ADMIN").contains(a.getAuthority())); }
    private static FachlicherFehler fehlt() { return new FachlicherFehler(NICHT_GEFUNDEN,"Antrag oder Dokument nicht gefunden."); }
    private static FachlicherFehler zuordnungGeaendert() { return new FachlicherFehler(KONFLIKT,"Ihre Mitarbeiterzuordnung wurde inzwischen geändert. Bitte neu laden."); }
    private static FachlicherFehler konflikt() { return new FachlicherFehler(KONFLIKT,"Antrag oder Bestandsdaten wurden inzwischen geändert. Bitte neu laden; veraltete Anträge ablehnen und erneut einreichen lassen."); }
    private static FachlicherFehler ungueltig(String text) { return new FachlicherFehler(UNGUELTIG,text); }
}
