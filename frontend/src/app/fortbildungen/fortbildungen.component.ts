import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Abgleich, EntwicklungApi, Kenntnis, Nachweis, Person, Profil, fehlertext, heute } from '../entwicklung/entwicklung-api.service';
import { FortbildungsApi, Schulung, Schulungsentwurf, Teilnahme } from './fortbildungs-api.service';

@Component({ selector: 'app-fortbildungen', standalone: true, imports: [FormsModule, RouterLink],
  templateUrl: './fortbildungen.component.html', styleUrl: './fortbildungen.component.css' })
export class FortbildungenComponent implements OnInit {
  private readonly api = inject(FortbildungsApi);
  private readonly entwicklung = inject(EntwicklungApi);
  private readonly route = inject(ActivatedRoute);
  @ViewChild('titelFeld') titelFeld?: ElementRef<HTMLInputElement>;
  @ViewChild('abschlussFeld') abschlussFeld?: ElementRef<HTMLInputElement>;
  readonly schulungen = signal<Schulung[]>([]); readonly katalog = signal<Kenntnis[]>([]);
  readonly personen = signal<Person[]>([]); readonly profile = signal<Profil[]>([]);
  readonly ausgewaehlt = signal<Schulung | null>(null); readonly teilnahme = signal<Teilnahme | null>(null);
  readonly vorhandenerNachweis = signal<Nachweis | null>(null); readonly ergebnis = signal<Abgleich[]>([]);
  readonly abgeglichen = signal(false); readonly arbeitet = signal(false); readonly geladen = signal(false);
  readonly fehler = signal(''); readonly meldung = signal('');
  entwurf: Schulungsentwurf = this.leer();
  schulungId = 0; personId = 0; profilId: number | null = null; stichtag = heute();
  auswahl: number[] = []; personenSuche = ''; ersetzen = false;
  abschluss = { erworbenAm: heute(), gueltigBis: '', nachweis: '' };

  ngOnInit(): void {
    void this.ausfuehren(async () => {
      await this.laden();
      const params = this.route.snapshot.queryParamMap;
      const person = Number(params.get('mitarbeiterId')), kenntnis = Number(params.get('kenntnisId')), niveau = Number(params.get('niveau'));
      if (this.personen().some(p => p.id === person) && this.katalog().some(k => k.id === kenntnis) && Number.isInteger(niveau) && niveau >= 1 && niveau <= 5) {
        this.personId = person; this.vorbereiten(kenntnis, niveau);
      }
    });
  }
  private leer(): Schulungsentwurf { return { titel: '', anbieter: '', beginn: heute(), ende: heute(), kenntnisId: 0, niveau: 1 }; }
  private async laden(): Promise<void> {
    const [s, k, p, profile] = await Promise.all([this.api.liste(), this.entwicklung.laden<Kenntnis[]>('kenntnisse'), this.entwicklung.laden<Person[]>('personen'), this.entwicklung.laden<Profil[]>('profile')]);
    this.schulungen.set(s); this.katalog.set(k); this.personen.set(p); this.profile.set(profile); this.geladen.set(true);
  }
  neu(): void {
    this.ausgewaehlt.set(null); this.schulungId = 0; this.entwurf = this.leer(); this.auswahl = [];
    this.abschlussAbbrechen(); this.fehler.set(''); this.meldung.set(''); this.titelFeld?.nativeElement.focus();
  }
  private uebernehmen(s: Schulung): void {
    this.ausgewaehlt.set(s); this.schulungId = s.id;
    const { teilnahmen, ...e } = s; this.entwurf = e;
    this.schulungen.update(liste => [...liste.filter(a => a.id !== s.id), s].sort((a, b) => b.beginn.localeCompare(a.beginn) || b.id - a.id));
  }
  async waehlen(): Promise<void> {
    if (!this.schulungId) { this.neu(); return; }
    await this.ausfuehren(async () => {
      const schulung = await this.api.laden(this.schulungId);
      this.abschlussAbbrechen(); this.auswahl = []; this.uebernehmen(schulung);
    });
    this.schulungId = this.ausgewaehlt()?.id ?? 0;
  }
  async neuLaden(): Promise<void> {
    await this.ausfuehren(async () => {
      const id = this.ausgewaehlt()?.id; await this.laden(); this.abschlussAbbrechen(); this.auswahl = [];
      if (id) { const s = this.schulungen().find(s => s.id === id); if (s) this.uebernehmen(s); else this.neu(); }
    }, 'Daten neu geladen. Bitte die Angaben erneut prüfen.');
  }
  async speichern(): Promise<void> {
    await this.ausfuehren(async () => this.uebernehmen(await this.api.speichern(this.entwurf)), 'Schulung gespeichert.');
  }
  vorbereitet(a: Abgleich): void { this.vorbereiten(a.kenntnisId, a.soll); }
  private vorbereiten(kenntnis: number, niveau: number): void {
    this.neu(); this.entwurf = { ...this.leer(), kenntnisId: kenntnis, niveau, titel: this.kenntnisName(kenntnis) };
    this.auswahl = [this.personId]; this.meldung.set('Schulung aus dem Bedarf vorbereitet. Zeitraum und Anbieter ergänzen, dann speichern und die Person zuordnen.');
    this.titelFeld?.nativeElement.focus();
  }
  async abgleichen(): Promise<void> {
    await this.ausfuehren(async () => {
      this.abgeglichen.set(false);
      const params: Record<string, string | number> = { mitarbeiterId: this.personId, stichtag: this.stichtag };
      if (this.profilId !== null) params['profilId'] = this.profilId;
      this.ergebnis.set((await this.entwicklung.laden<Abgleich[]>('abgleich', params)).filter(a => a.differenz > 0));
      this.abgeglichen.set(true);
    });
  }
  verfuegbarePersonen(): Person[] {
    const ids = new Set(this.ausgewaehlt()?.teilnahmen.map(t => t.mitarbeiterId) ?? []);
    const suche = this.personenSuche.trim().toLocaleLowerCase('de');
    return this.personen().filter(p => !ids.has(p.id) && `${p.name} ${p.personalnummer}`.toLocaleLowerCase('de').includes(suche));
  }
  personWaehlen(id: number): void { this.auswahl = this.auswahl.includes(id) ? this.auswahl.filter(a => a !== id) : [...this.auswahl, id]; }
  async zuordnen(): Promise<void> {
    const s = this.ausgewaehlt(); if (!s) return;
    await this.ausfuehren(async () => { this.uebernehmen(await this.api.zuordnen(s, this.auswahl)); this.auswahl = []; }, 'Teilnehmende zugeordnet.');
  }
  async entfernen(t: Teilnahme): Promise<void> {
    const s = this.ausgewaehlt(); if (!s || !confirm(`${t.name} von dieser Schulung abmelden?`)) return;
    await this.ausfuehren(async () => this.uebernehmen(await this.api.entfernen(s, t.mitarbeiterId)), 'Teilnahme entfernt.');
  }
  async abschlussOeffnen(t: Teilnahme): Promise<void> {
    const s = this.ausgewaehlt(); if (!s) return;
    await this.ausfuehren(async () => {
      const [aktuell, nachweise] = await Promise.all([this.api.laden(s.id), this.entwicklung.laden<Nachweis[]>('nachweise', { mitarbeiterId: t.mitarbeiterId })]);
      this.uebernehmen(aktuell);
      const teilnahme = aktuell.teilnahmen.find(p => p.mitarbeiterId === t.mitarbeiterId && !p.abgeschlossen);
      if (!teilnahme) { this.abschlussAbbrechen(); this.fehler.set('Diese Teilnahme ist inzwischen abgeschlossen oder entfernt.'); return; }
      this.teilnahme.set(teilnahme); this.vorhandenerNachweis.set(nachweise.find(n => n.kenntnisId === aktuell.kenntnisId) ?? null);
      this.ersetzen = false; this.abschluss = { erworbenAm: aktuell.ende, gueltigBis: '', nachweis: '' };
      setTimeout(() => this.abschlussFeld?.nativeElement.focus());
    });
  }
  abschlussAbbrechen(): void { this.teilnahme.set(null); this.vorhandenerNachweis.set(null); this.ersetzen = false; }
  async abschliessen(): Promise<void> {
    const s = this.ausgewaehlt(), t = this.teilnahme(), n = this.vorhandenerNachweis();
    if (!s || !t || (n && !this.ersetzen)) return;
    await this.ausfuehren(async () => {
      this.uebernehmen(await this.api.abschliessen(s, t.mitarbeiterId, { ...this.abschluss, gueltigBis: this.abschluss.gueltigBis || null,
        version: s.version, vorhandenerNachweisId: n?.id ?? null, nachweisVersion: n?.version ?? null }));
      this.abschlussAbbrechen(); this.abgeglichen.set(false);
    }, 'Teilnahme abgeschlossen und Kenntnisnachweis gespeichert.');
  }
  async loeschen(): Promise<void> {
    const s = this.ausgewaehlt(); if (!s || !confirm(`Schulung „${s.titel}“ mit ihren offenen Teilnahmen löschen?`)) return;
    await this.ausfuehren(async () => { await this.api.loeschen(s); this.schulungen.update(liste => liste.filter(a => a.id !== s.id)); this.neu(); }, 'Schulung gelöscht.');
  }
  hatAbschluss(): boolean { return this.ausgewaehlt()?.teilnahmen.some(t => t.abgeschlossen) ?? false; }
  ungespeichert(): boolean {
    const s = this.ausgewaehlt();
    return !!s && (this.entwurf.titel !== s.titel || this.entwurf.anbieter !== s.anbieter || this.entwurf.beginn !== s.beginn
      || this.entwurf.ende !== s.ende || this.entwurf.kenntnisId !== s.kenntnisId || this.entwurf.niveau !== s.niveau);
  }
  kenntnisName(id: number): string { return this.katalog().find(k => k.id === id)?.name ?? 'Kenntnis'; }
  async ausfuehren(aktion: () => Promise<void>, meldung = ''): Promise<void> {
    if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); if (meldung) this.meldung.set(meldung); } catch (e) { this.fehler.set(fehlertext(e)); } finally { this.arbeitet.set(false); }
  }
}
