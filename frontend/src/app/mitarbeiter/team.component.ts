import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { OrganisationApiService } from '../organisation/organisation-api.service';
import { SitzungService } from '../sitzung/sitzung.service';
import { Component, DestroyRef, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { firstValueFrom, forkJoin, of } from 'rxjs';
import { MitarbeiterApiService } from './mitarbeiter-api.service';
import { Mitarbeiter, Statistik } from '../shared/model';
import { MitarbeiterEditorComponent } from './mitarbeiter-editor.component';
import { FormsModule } from '@angular/forms';
import { Filter, Organisation, heutigesDatum } from '../shared/model';

import { MitarbeiterDetailsComponent } from '../abwesenheit/mitarbeiter-details.component';
import { PersonaldatenComponent } from '../personaldaten/personaldaten.component';

@Component({
  selector: 'app-team',
  standalone: true,
  imports: [MitarbeiterEditorComponent, FormsModule, MitarbeiterDetailsComponent, PersonaldatenComponent],
  templateUrl: './team.component.html',
})
export class TeamComponent implements OnInit {
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly api = inject(MitarbeiterApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly zahlen = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 2 });
  private readonly datum = new Intl.DateTimeFormat('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' });
  private anfrage = 0;
  private suchTimer?: ReturnType<typeof setTimeout>;
  private meldungsTimer?: ReturnType<typeof setTimeout>;
  private organisationenGeladen = false;

  @ViewChild('loeschdialog') private loeschdialog!: ElementRef<HTMLDialogElement>;
  @ViewChild(MitarbeiterEditorComponent) private editor?: MitarbeiterEditorComponent;
  @ViewChild(MitarbeiterDetailsComponent) private profil?: MitarbeiterDetailsComponent;
  @ViewChild(PersonaldatenComponent) private personaldatenDialog?: PersonaldatenComponent;
  readonly personaldaten = signal<Mitarbeiter | null>(null);
  readonly organisationswechsel = signal(false);
  readonly neuanlage = signal<number | null>(null);
  readonly fristZiel = signal({ bereich: '', datensatz: '' });

  readonly groesse = 10;
  readonly sortierSpalten = [
    { wert: 'personalnummer', titel: 'Personal-Nr.' },
    { wert: 'titel', titel: 'Titel' },
    { wert: 'nachname', titel: 'Nachname' },
    { wert: 'vorname', titel: 'Vorname' },
    { wert: 'geburtsname', titel: 'Geburtsname' },
    { wert: 'kuerzel', titel: 'Kürzel' },
    { wert: 'geburtsdatum', titel: 'Geburtsdatum' },
    { wert: 'eintrittsdatum', titel: 'Eintrittsdatum' },
    { wert: 'austrittsdatum', titel: 'Austrittsdatum' },
    { wert: 'organisationseinheit', titel: 'Org.-Einheit' },
    { wert: 'stelle', titel: 'Stelle' },
    { wert: 'beschaeftigungsart', titel: 'Beschäftigungsart / Beschäftigung' },
    { wert: 'abwesenheitAmStichtag', titel: 'Abwesenheit am Stichtag' },
    { wert: 'wochenstunden', titel: 'Wochenstunden' },
  ];
  readonly seite = signal(0);
  readonly suche = signal('');
  readonly eintraege = signal<Mitarbeiter[]>([]);
  readonly gesamt = signal(0);
  readonly statistik = signal<Statistik | null>(null);
  readonly sitzung = inject(SitzungService).sitzung;
  private readonly organisationApi = inject(OrganisationApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  readonly organisationen = signal<Organisation[]>([]);
  readonly details = signal<Mitarbeiter | null>(null);
  readonly darfSchreiben = computed(() => this.sitzung()?.rolle === 'PERSONAL' || this.sitzung()?.rolle === 'ADMIN');
  organisationId = '';
  beschaeftigung = 'ALLE';
  stichtag = heutigesDatum();
  sortierung = 'personalnummer';
  richtung = 'ASC';
  readonly laedt = signal(true);
  readonly ladefehler = signal('');
  readonly meldung = signal('');
  readonly editorGeoeffnet = signal(false);
  readonly bearbeitet = signal<Mitarbeiter | null>(null);
  readonly loeschPerson = signal<Mitarbeiter | null>(null);
  readonly loeschfehler = signal('');
  readonly loescht = signal(false);
  readonly seiteninfo = computed(() => {
    if (this.ladefehler()) return 'Daten konnten nicht geladen werden';
    if (this.gesamt() === 0) return 'Keine Einträge';
    return `${this.seite() * this.groesse + 1}–${Math.min((this.seite() + 1) * this.groesse, this.gesamt())} von ${this.gesamt()} Mitarbeitenden`;
  });

  constructor() {
    this.destroyRef.onDestroy(() => {
      ++this.anfrage;
      clearTimeout(this.suchTimer);
      clearTimeout(this.meldungsTimer);
    });
  }

  ngOnInit(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(p => {
      this.suche.set(p.get('suche') ?? '');
      this.organisationId = p.get('organisationseinheitId') ?? '';
      this.beschaeftigung = p.get('beschaeftigung') ?? 'ALLE';
      this.stichtag = p.get('stichtag') ?? heutigesDatum();
      this.sortierung = p.get('sortierung') ?? 'personalnummer';
      this.richtung = p.get('richtung') ?? 'ASC';
      const seite = Number(p.get('seite') ?? 0);
      this.seite.set(Number.isSafeInteger(seite) && seite >= 0 ? seite : 0);
      void this.laden();
      const personId = Number(p.get('personaldatenId'));
      if (Number.isSafeInteger(personId) && personId > 0 && this.darfSchreiben()) {
        this.fristZiel.set({ bereich: p.get('erweiterung') ?? '', datensatz: p.get('datensatz') ?? '' });
        void firstValueFrom(this.api.finden(personId)).then(person => this.personaldaten.set(person))
          .catch(e => this.ladefehler.set(this.fehlertext(e)));
      }
    });
  }
  verlassenErlauben(): boolean | Promise<boolean> {
    return this.personaldatenDialog?.verlassenErlauben() ?? this.editor?.verlassenErlauben() ?? true;
  }

  async personaldatenSchliessen(id: number): Promise<void> {
    this.organisationswechsel.set(false);
    this.neuanlage.set(null);
    this.personaldaten.set(null);
    this.fristZiel.set({ bereich: '', datensatz: '' });
    if (this.route.snapshot.queryParamMap.has('personaldatenId')) await this.router.navigate([], {
      relativeTo: this.route, queryParamsHandling: 'merge', queryParams: { personaldatenId: null, erweiterung: null, datensatz: null }, replaceUrl: true,
    });
    if (this.details()?.id === id) {
      try {
        const person = await firstValueFrom(this.api.finden(id));
        if (this.details()?.id !== id) return;
        this.details.set(person);
        await this.profil?.laden();
      } catch (e) { this.profil?.fehler.set(this.fehlertext(e)); }
    } else {
      await this.laden();
      this.profilEinstiegFokussieren(id);
    }
  }

  async profilOeffnen(person: Mitarbeiter): Promise<void> {
    try { this.details.set(await firstValueFrom(this.api.finden(person.id))); }
    catch (e) { this.ladefehler.set(this.fehlertext(e)); }
  }

  async profilSchliessen(id: number): Promise<void> {
    this.details.set(null);
    await this.laden();
    this.profilEinstiegFokussieren(id);
  }

  private profilEinstiegFokussieren(id: number): void {
    setTimeout(() => this.element.nativeElement.querySelector<HTMLButtonElement>(`button[data-profil-id="${id}"]`)?.focus());
  }
  statusText(wert: string): string {
    return ({ AKTIV: 'Aktuell beschäftigt', KUENFTIG: 'Künftiger Eintritt', AUSGESCHIEDEN: 'Ausgeschieden',
      URLAUB: 'Urlaub', KRANKHEIT: 'Krankheit', ELTERNZEIT: 'Elternzeit', SONSTIGES: 'Sonstiges' } as Record<string, string>)[wert] ?? 'Keine erfasste Abwesenheit';
  }
  private async urlAktualisieren(): Promise<void> {
    const geaendert = await this.router.navigate([], { relativeTo: this.route,
      queryParams: { ...this.filter(), suche: this.suche() || null, seite: this.seite() || null } });
    if (!geaendert) await this.laden();
  }
  private filter(): Filter {
    return { stichtag: this.stichtag, beschaeftigung: this.beschaeftigung,
      sortierung: this.sortierung, richtung: this.richtung,
      ...(this.organisationId ? { organisationseinheitId: Number(this.organisationId) } : {}) };
  }
  filterAendern(): void { clearTimeout(this.suchTimer); this.seite.set(0); void this.urlAktualisieren(); }
  spalteSortieren(spalte: string): void {
    this.richtung = this.sortierung === spalte && this.richtung === 'ASC' ? 'DESC' : 'ASC';
    this.sortierung = spalte;
    this.filterAendern();
  }
  ariaSortierung(spalte: string): 'ascending' | 'descending' | null {
    return this.sortierung === spalte ? (this.richtung === 'ASC' ? 'ascending' : 'descending') : null;
  }
  zuruecksetzen(): void {
    this.suche.set(''); this.organisationId = ''; this.beschaeftigung = 'ALLE';
    this.stichtag = heutigesDatum(); this.sortierung = 'personalnummer'; this.richtung = 'ASC'; this.filterAendern();
  }
  hatFilter(): boolean { return !!this.suche() || !!this.organisationId || this.beschaeftigung !== 'ALLE'; }

  zahl(wert: number | null | undefined): string {
    return wert == null ? '–' : this.zahlen.format(wert);
  }

  formatiertesDatum(text: string | null): string {
    if (!text) return '—';
    const [jahr, monat, tag] = text.split('-').map(Number);
    const datum = new Date(0);
    datum.setFullYear(jahr, monat - 1, tag);
    datum.setHours(0, 0, 0, 0);
    return this.datum.format(datum);
  }

  async laden(organisationenAktualisieren = false): Promise<void> {
    const nummer = ++this.anfrage;
    if (!this.stichtag || !/^\d{4}-\d{2}-\d{2}$/.test(this.stichtag)) {
      this.ladefehler.set('Bitte einen gültigen Stichtag angeben.'); this.laedt.set(false); return;
    }
    this.laedt.set(true);
    this.ladefehler.set('');
    try {
      const { liste, statistik, organisationen } = await firstValueFrom(forkJoin({
        liste: this.api.liste(this.suche(), this.seite(), this.groesse, this.filter()),
        statistik: this.api.statistik({ ...this.filter(), suche: this.suche() }),
        organisationen: !this.organisationenGeladen || organisationenAktualisieren
          ? this.organisationApi.organisationen() : of(this.organisationen()),
      }));
      if (nummer !== this.anfrage) return;
      this.organisationen.set(organisationen);
      this.organisationenGeladen = true;
      if (liste.mitarbeiter.length === 0 && this.seite() > 0) {
        this.seite.set(Math.max(0, Math.ceil(liste.gesamt / this.groesse) - 1));
        await this.urlAktualisieren();
        return;
      }
      this.eintraege.set(liste.mitarbeiter);
      this.gesamt.set(liste.gesamt);
      this.statistik.set(statistik);
    } catch (fehler) {
      if (nummer === this.anfrage) this.ladefehler.set(this.fehlertext(fehler));
    } finally {
      if (nummer === this.anfrage) this.laedt.set(false);
    }
  }

  sucheAendern(text: string): void {
    clearTimeout(this.suchTimer);
    this.suche.set(text);
    this.suchTimer = setTimeout(() => {
      this.seite.set(0);
      void this.urlAktualisieren();
    }, 250);
  }

  blaettern(richtung: number): void {
    if (this.laedt() || this.ladefehler()) return;
    const seite = this.seite() + richtung;
    if (seite < 0 || seite * this.groesse >= this.gesamt()) return;
    this.seite.set(seite);
    void this.urlAktualisieren();
  }

  async oeffnen(person: Mitarbeiter | null = null): Promise<void> {
    if (!this.darfSchreiben()) return;
    try {
      this.bearbeitet.set(person ? await firstValueFrom(this.api.finden(person.id)) : null);
      this.editorGeoeffnet.set(true);
    } catch (e) { this.ladefehler.set(this.fehlertext(e)); }
  }

  gespeichert(person: Mitarbeiter): void {
    this.editorGeoeffnet.set(false);
    if (this.details()?.id === person.id) {
      this.details.set(person);
      void this.profil?.laden();
    }
    this.meldungAnzeigen(this.bearbeitet()
      ? 'Änderungen wurden gespeichert.'
      : `Mitarbeiter mit Personal-Nr. ${person.personalnummer} wurde hinzugefügt.`);
    void this.laden();
  }

  anlageFortsetzen(person: Mitarbeiter): void {
    this.gespeichert(person);
    this.neuanlage.set(person.id);
    this.fristZiel.set({ bereich: '', datensatz: '' });
    this.details.set(person);
    this.personaldaten.set(person);
  }

  organisationAendern(person: Mitarbeiter): void {
    this.editorGeoeffnet.set(false);
    if (this.details()?.id === person.id) this.details.set(person);
    this.organisationswechsel.set(true);
    this.personaldaten.set(person);
  }

  loeschenOeffnen(person: Mitarbeiter): void {
    this.loeschPerson.set(person);
    this.loeschfehler.set('');
    this.loeschdialog.nativeElement.showModal();
  }

  loeschenAbbrechen(event?: Event): void {
    event?.preventDefault();
    if (!this.loescht()) this.loeschdialog.nativeElement.close();
  }

  async loeschenBestaetigen(): Promise<void> {
    const person = this.loeschPerson();
    if (!person || this.loescht()) return;
    this.loescht.set(true);
    this.loeschfehler.set('');
    try {
      await firstValueFrom(this.api.loeschen(person));
      this.loeschdialog.nativeElement.close();
      this.meldungAnzeigen('Mitarbeiter wurde gelöscht.');
      await this.laden();
    } catch (fehler) {
      this.loeschfehler.set(this.fehlertext(fehler));
    } finally {
      this.loescht.set(false);
    }
  }

  private meldungAnzeigen(text: string): void {
    clearTimeout(this.meldungsTimer);
    this.meldung.set(text);
    this.meldungsTimer = setTimeout(() => this.meldung.set(''), 5000);
  }

  private fehlertext(fehler: unknown): string {
    return fehler instanceof Error ? fehler.message : 'Ein unerwarteter Fehler ist aufgetreten.';
  }
}
