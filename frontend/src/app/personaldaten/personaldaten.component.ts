import { AfterViewInit, Component, LOCALE_ID, ElementRef, HostListener, ViewChild, inject, input, output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe, registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { Mitarbeiter, Organisation } from '../shared/model';
import { Person, Profil, Planstelle, fehlertext } from '../entwicklung/entwicklung-api.service';
import { HistorieComponent } from '../mitarbeiter/historie.component';
import { Personalakte, Personaldaten, Revision, Beschaeftigungszeit } from './personaldaten.model';
import { PersonalstammComponent } from './personalstamm.component';
import { VerguetungComponent } from './verguetung.component';
import { ErweiterungenComponent } from './erweiterungen.component';
import { PersonaldokumenteComponent } from './personaldokumente.component';

registerLocaleData(localeDe);

@Component({ selector: 'app-personaldaten', standalone: true,
  providers: [{ provide: LOCALE_ID, useValue: 'de-DE' }],
  imports: [FormsModule, DatePipe, DecimalPipe, RouterLink, HistorieComponent, PersonalstammComponent, VerguetungComponent, PersonaldokumenteComponent, ErweiterungenComponent],
  templateUrl: './personaldaten.component.html', styleUrl: './personaldaten.component.css' })
export class PersonaldatenComponent implements AfterViewInit {
  private readonly http = inject(HttpClient);
  readonly person = input.required<Mitarbeiter>();
  readonly geschlossen = output<void>();
  @ViewChild(ErweiterungenComponent) private erweiterungen?: ErweiterungenComponent;
  erweiterungenBesucht = false;
  @ViewChild(HistorieComponent) private historieEditor?: HistorieComponent;
  @ViewChild(PersonaldokumenteComponent) private dokumente?: PersonaldokumenteComponent;
  organisationBesucht = false;
  dokumenteBesucht = false;
  @ViewChild('dialog') private dialog!: ElementRef<HTMLDialogElement>;
  readonly akte = signal<Personalakte | null>(null);
  readonly daten = signal<Personaldaten | null>(null);
  readonly personen = signal<Person[]>([]);
  readonly profile = signal<Profil[]>([]);
  readonly planstellen = signal<Planstelle[]>([]);
  readonly organisationen = signal<Organisation[]>([]);
  readonly revisionen = signal<Revision[]>([]);
  readonly historieGesamt = signal(0);
  readonly offeneStaende = signal(new Set<number>());
  readonly fehler = signal(''); readonly meldung = signal('');
  readonly arbeitet = signal(false); readonly kindArbeitet = signal(false);
  readonly geladen = signal(false);
  readonly bereiche = ['Stammdaten', 'Beschäftigung', 'Organisation', 'Ergänzungen', 'Vergütung', 'Dokumente', 'Historie'];
  bereich = 'Stammdaten'; historieSeite = 0;
  zeit = { beginn: '', ende: '', austrittsgrund: '', kuendigungsdatum: null as string | null };
  neuerEintritt = '';
  planstelleId: number | null = null;
  private original = '';
  private fokus: HTMLElement | null = null;
  ngAfterViewInit(): void { this.fokus = document.activeElement as HTMLElement; this.dialog.nativeElement.showModal(); void this.laden(); }
  private basisGeaendert(): boolean { return !!this.daten() && JSON.stringify(this.daten()) !== this.original; }
  geaendert(): boolean {
    return this.basisGeaendert() || Object.values(this.zeit).some(Boolean) || !!this.neuerEintritt || this.planstelleId !== null
      || (this.erweiterungen?.geaendert() ?? false) || (this.historieEditor?.entwurfGeaendert() ?? false)
      || (this.dokumente?.geaendert() ?? false);
  }
  verlassenErlauben(): boolean { return !this.arbeitet() && !this.kindArbeitet() && (!this.geaendert() || confirm('Ungespeicherte Personaldaten verwerfen?')); }
  @HostListener('window:beforeunload', ['$event']) vorVerlassen(e: BeforeUnloadEvent): void { if (this.geaendert() || this.arbeitet() || this.kindArbeitet()) e.preventDefault(); }
  schliessen(event?: Event): void { event?.preventDefault(); event?.stopPropagation(); if (this.verlassenErlauben()) { this.dialog.nativeElement.close(); this.fokus?.focus(); this.geschlossen.emit(); } }
  async bereichWaehlen(b: string): Promise<void> {
    this.bereich = b; this.meldung.set('');
    if (b === 'Ergänzungen') { this.erweiterungenBesucht = true; await this.erweiterungen?.fteAktualisieren(); }
    if (b === 'Organisation') {
      this.organisationBesucht = true;
      if (!this.historieEditor?.entwurfGeaendert()) await this.historieEditor?.laden();
    }
    if (b === 'Dokumente') {
      this.dokumenteBesucht = true;
      if (!this.dokumente?.geaendert()) await this.dokumente?.laden();
    }
    if (b === 'Historie') await this.historieLaden();
  }
  async neuLaden(): Promise<void> {
    if (this.geaendert() && !confirm('Ungespeicherte Personaldaten verwerfen und neu laden?')) return;
    await this.ausfuehren(async () => {
      await this.datenLaden();
      this.zeit = { beginn: '', ende: '', austrittsgrund: '', kuendigungsdatum: null };
      this.neuerEintritt = ''; this.planstelleId = null;
      this.historieEditor?.bearbeiten(); this.dokumente?.entwurfVerwerfen();
      await this.historieEditor?.laden(); await this.dokumente?.laden(); await this.erweiterungen?.laden();
    });
  }
  private async laden(): Promise<void> { await this.ausfuehren(() => this.datenLaden()); }
  private async datenLaden(): Promise<void> {
    const [akte, personen, profile, planstellen, organisationen] = await Promise.all([
      this.get<Personalakte>(this.pfad()), this.get<Person[]>('/api/entwicklung/personen'), this.get<Profil[]>('/api/entwicklung/profile'),
      this.get<Planstelle[]>('/api/entwicklung/planstellen'), this.get<Organisation[]>('/api/organisationseinheiten'),
    ]);
    this.uebernehmen(akte); this.personen.set(personen); this.profile.set(profile); this.planstellen.set(planstellen); this.organisationen.set(organisationen); this.geladen.set(true);
  }
  async speichern(): Promise<void> {
    await this.ausfuehren(async () => { this.uebernehmen(await firstValueFrom(mitFehlern(this.http.put<Personalakte>(this.pfad(), this.daten())))); this.meldung.set('Personaldaten gespeichert.'); });
  }
  async zeitSpeichern(): Promise<void> {
    if (!this.sauber()) return;
    await this.ausfuehren(async () => {
      this.uebernehmen(await this.post<Personalakte>('/beschaeftigungszeiten', { ...this.zeit, version: this.daten()!.version }));
      this.zeit = { beginn: '', ende: '', austrittsgrund: '', kuendigungsdatum: null }; this.meldung.set('Beschäftigungszeit gespeichert.');
    });
  }
  async zeitLoeschen(z: Beschaeftigungszeit): Promise<void> {
    if (!this.sauber() || !confirm('Diese frühere Beschäftigungszeit entfernen? Sie bleibt im gespeicherten Verlauf nachvollziehbar.')) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.delete(this.pfad() + '/beschaeftigungszeiten/' + z.id, { params: { version: this.daten()!.version } })));
      this.uebernehmen(await this.get<Personalakte>(this.pfad())); this.meldung.set('Beschäftigungszeit entfernt.');
    });
  }
  async wiedereintritt(): Promise<void> {
    if (!this.sauber()) return;
    await this.ausfuehren(async () => {
      this.uebernehmen(await this.post<Personalakte>('/wiedereintritt', { eintritt: this.neuerEintritt, version: this.daten()!.version, mitarbeiterVersion: this.akte()!.person.version }));
      this.neuerEintritt = ''; this.meldung.set('Wiedereintritt gespeichert. Der bisherige Zeitraum bleibt erhalten.');
    });
  }
  async historieLaden(): Promise<void> {
    await this.ausfuehren(async () => { const h = await this.get<{ eintraege: Revision[]; gesamt: number }>(this.pfad() + '/historie?seite=' + this.historieSeite); this.revisionen.set(h.eintraege); this.historieGesamt.set(h.gesamt); });
  }
  standOeffnen(id: number, e: Event): void {
    const element = e.target;
    if (element instanceof HTMLDetailsElement) this.offeneStaende.update(alt => { const neu = new Set(alt); if (element.open) neu.add(id); else neu.delete(id); return neu; });
  }
  async organisationGeaendert(): Promise<void> {
    try { this.akte.set(await this.get<Personalakte>(this.pfad())); } catch (e) { this.fehler.set(fehlertext(e)); }
  }
  zugeordnetePlanstellen(): Planstelle[] { return this.planstellen().filter(p => p.mitarbeiterId === this.person().id); }
  freiePlanstellen(): Planstelle[] { return this.planstellen().filter(p => p.mitarbeiterId === null); }
  profilName(id: number): string { return this.profile().find(p => p.id === id)?.name ?? 'Unbekanntes Profil'; }
  organisationsName(id: number): string { return this.organisationen().find(o => o.id === id)?.name ?? 'Unbekannte Organisation'; }
  async planstelleZuordnen(): Promise<void> {
    const p = this.planstellen().find(p => p.id === this.planstelleId);
    if (p) await this.planstelleSpeichern(p, this.person().id);
  }
  async planstelleFreigeben(p: Planstelle): Promise<void> { if (confirm('Die Besetzung dieser Planstelle aufheben?')) await this.planstelleSpeichern(p, null); }
  private async planstelleSpeichern(p: Planstelle, person: number | null): Promise<void> {
    await this.ausfuehren(async () => {
      const { id, ...eingabe } = p;
      await firstValueFrom(mitFehlern(this.http.put('/api/entwicklung/planstellen/' + id, { ...eingabe, mitarbeiterId: person })));
      this.planstellen.set(await this.get<Planstelle[]>('/api/entwicklung/planstellen')); this.planstelleId = null; this.meldung.set('Planstellenbesetzung gespeichert.');
    });
  }
  private sauber(): boolean { if (!this.basisGeaendert()) return true; this.fehler.set('Bitte zuerst die geänderten Personaldaten speichern.'); return false; }
  private uebernehmen(akte: Personalakte): void { this.akte.set(akte); this.daten.set(structuredClone(akte.daten)); this.original = JSON.stringify(akte.daten); }
  private pfad(): string { return `/api/mitarbeiter/${this.person().id}/personaldaten`; }
  private get<T>(pfad: string): Promise<T> { return firstValueFrom(mitFehlern(this.http.get<T>(pfad))); }
  private post<T>(pfad: string, daten: unknown): Promise<T> { return firstValueFrom(mitFehlern(this.http.post<T>(this.pfad() + pfad, daten))); }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet() || this.kindArbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(fehlertext(e)); } finally { this.arbeitet.set(false); }
  }
}
