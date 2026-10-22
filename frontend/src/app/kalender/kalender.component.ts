import { Component, DestroyRef, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Abwesenheit, Mitarbeiter, Organisation, heutigesDatum } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';
import { OrganisationApiService } from '../organisation/organisation-api.service';
import { SitzungService } from '../sitzung/sitzung.service';
import { MitarbeiterDetailsComponent } from '../abwesenheit/mitarbeiter-details.component';
import { AbwesenheitApiService } from '../abwesenheit/abwesenheit-api.service';

interface KalenderPerson { id: number; name: string; personalnummer: number; organisationseinheit: string; historieBekannt: boolean; abwesenheiten: Abwesenheit[]; }
interface KalenderSeite { beginn: string; ende: string; eintraege: KalenderPerson[]; gesamt: number; }
@Component({ selector: 'app-kalender', standalone: true, imports: [FormsModule, DatePipe, MitarbeiterDetailsComponent], templateUrl: './kalender.component.html', styleUrl: './kalender.component.css' })
export class KalenderComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly orgApi = inject(OrganisationApiService);
  private readonly abwesenheitApi = inject(AbwesenheitApiService);
  readonly sitzung = inject(SitzungService).sitzung;
  readonly darfSchreiben = computed(() => this.sitzung()?.rolle === 'ADMIN' || this.sitzung()?.rolle === 'PERSONAL');
  readonly daten = signal<KalenderSeite | null>(null);
  readonly organisationen = signal<Organisation[]>([]);
  readonly details = signal<Mitarbeiter | null>(null);
  readonly fehler = signal('');
  readonly laedt = signal(false);
  readonly loescht = signal(false);
  readonly meldung = signal('');
  readonly auswahl = signal<{ person: KalenderPerson; abwesenheit: Abwesenheit } | null>(null);
  @ViewChild('monatsuebersicht') private monatsuebersicht?: ElementRef<HTMLElement>;
  monat = heutigesDatum().slice(0, 7);
  organisation = '';
  suche = '';
  seite = 0;
  private anfrage = 0;
  private suchTimer?: ReturnType<typeof setTimeout>;
  readonly arten: Record<string, string> = { URLAUB: 'Urlaub', KRANKHEIT: 'Krankheit', ELTERNZEIT: 'Elternzeit', SONSTIGES: 'Sonstiges' };
  constructor() {
    this.destroyRef.onDestroy(() => { ++this.anfrage; clearTimeout(this.suchTimer); });
  }
  async ngOnInit(): Promise<void> {
    try { this.organisationen.set(await firstValueFrom(this.orgApi.organisationen())); } catch (e) { this.fehler.set(this.text(e)); }
    await this.laden();
  }
  nachbarMonat(richtung: -1 | 1): string | null {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(this.monat) || this.monat.startsWith('0000')) return null;
    const index = +this.monat.slice(0, 4) * 12 + +this.monat.slice(5) - 1 + richtung;
    if (index < 12 || index >= 10000 * 12) return null;
    return `${String(Math.floor(index / 12)).padStart(4, '0')}-${String(index % 12 + 1).padStart(2, '0')}`;
  }
  async monatWechseln(richtung: -1 | 1): Promise<void> {
    const monat = this.nachbarMonat(richtung);
    if (!monat || this.laedt() || this.loescht()) return;
    this.monat = monat;
    await this.laden(true);
  }
  async laden(neu = false): Promise<void> {
    clearTimeout(this.suchTimer);
    if (this.destroyRef.destroyed) return;
    this.auswahl.set(null);
    if (neu) this.seite = 0;
    const nr = ++this.anfrage;
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(this.monat) || this.monat.startsWith('0000')) { this.fehler.set('Bitte einen gültigen Monat wählen.'); this.daten.set(null); this.laedt.set(false); return; }
    this.laedt.set(true); this.fehler.set('');
    try {
      const params: Record<string, string | number> = { jahr: +this.monat.slice(0, 4), monat: +this.monat.slice(5), seite: this.seite, suche: this.suche.trim() };
      if (this.organisation) params['organisationseinheitId'] = this.organisation;
      const daten = await firstValueFrom(mitFehlern(this.http.get<KalenderSeite>('/api/kalender', { params })));
      if (nr === this.anfrage) this.daten.set(daten);
    } catch (e) { if (nr === this.anfrage) { this.fehler.set(this.text(e)); this.daten.set(null); } }
    finally { if (nr === this.anfrage) this.laedt.set(false); }
  }
  suchen(text: string): void {
    this.auswahl.set(null);
    this.suche = text;
    ++this.anfrage;
    clearTimeout(this.suchTimer);
    this.laedt.set(true);
    this.suchTimer = setTimeout(() => { void this.laden(true); }, 300);
  }
  tage(): string[] { const d = this.daten(); return d ? Array.from({ length: +d.ende.slice(8) }, (_, i) => d.beginn.slice(0, 8) + String(i + 1).padStart(2, '0')) : []; }
  wochenende(tag: string): boolean { return [0, 6].includes(new Date(tag + 'T12:00:00Z').getUTCDay()); }
  abwesenheit(p: KalenderPerson, tag: string): Abwesenheit | undefined { return p.abwesenheiten.find(a => a.beginn <= tag && a.ende >= tag); }
  markiert(p: KalenderPerson, a: Abwesenheit): boolean { return this.auswahl()?.person.id === p.id && this.auswahl()?.abwesenheit.id === a.id; }
  markieren(p: KalenderPerson, a: Abwesenheit): void {
    if (!this.darfSchreiben() || this.laedt() || this.loescht()) return;
    this.auswahl.set(this.markiert(p, a) ? null : { person: p, abwesenheit: a });
    this.fehler.set(''); this.meldung.set('');
  }
  auswahlAufheben(): void {
    if (this.loescht()) return;
    this.auswahl.set(null);
    this.monatsuebersicht?.nativeElement.focus();
  }
  datum(datum: string): string { return `${datum.slice(8)}.${datum.slice(5, 7)}.${datum.slice(0, 4)}`; }
  async loeschen(): Promise<void> {
    const auswahl = this.auswahl();
    if (!auswahl || !this.darfSchreiben() || this.laedt() || this.loescht()) return;
    const a = auswahl.abwesenheit;
    if (!confirm(`${this.arten[a.art]} von ${auswahl.person.name} (${this.datum(a.beginn)} – ${this.datum(a.ende)}) für den gesamten Zeitraum endgültig löschen?`)) return;
    this.loescht.set(true); this.fehler.set(''); this.meldung.set('');
    try {
      await firstValueFrom(this.abwesenheitApi.abwesenheitLoeschen(auswahl.person.id, a));
      this.meldung.set('Abwesenheit gelöscht.');
      await this.laden();
      this.monatsuebersicht?.nativeElement.focus();
    } catch (e) { this.fehler.set(this.text(e)); }
    finally { this.loescht.set(false); }
  }
  async oeffnen(id: number): Promise<void> {
    if (this.loescht()) return;
    this.auswahl.set(null);
    try { this.details.set(await firstValueFrom(mitFehlern(this.http.get<Mitarbeiter>(`/api/mitarbeiter/${id}`)))); }
    catch (e) { this.fehler.set(this.text(e)); }
  }
  private text(e: unknown): string { return e instanceof Error ? e.message : 'Kalender konnte nicht geladen werden.'; }
}
