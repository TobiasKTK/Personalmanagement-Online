import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { mitFehlern, ApiFehler } from '../shared/api-fehler';
import { heutigesDatum } from '../shared/model';
import { Feiertagskalender } from '../planung/feiertagskalender.component';

export interface Urlaubskonto { mitarbeiterId: number; jahr: number; eingerichtet: boolean; anspruch: number; uebertrag: number; arbeitstage: number[]; feiertage: string[]; genommen: number; geplant: number; beantragt: number; rest: number; verfuegbar: number; version: number | null; feiertagskalenderId: number | null; kalenderName: string; kalenderFeiertage: string[]; ausnahmen: string[]; }
@Component({ selector: 'app-urlaubskonto', standalone: true, imports: [FormsModule], templateUrl: './urlaubskonto.component.html' })
export class UrlaubskontoComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly person = input.required<number>();
  readonly darfSchreiben = input(false);
  readonly konto = signal<Urlaubskonto | null>(null);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly arbeitet = signal(false);
  readonly wochentage = ['Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag', 'Sonntag'];
  jahr = +heutigesDatum().slice(0, 4);
  anspruch = 0;
  uebertrag = 0;
  arbeitstage = [1, 2, 3, 4, 5];
  feiertage = '';
  kalenderId: number | null = null;
  ausnahmen = '';
  readonly kalender = signal<Feiertagskalender[]>([]);
  ngOnInit(): void { void this.laden(); }
  waehlen(tag: number, aktiv: boolean): void { this.arbeitstage = aktiv ? [...this.arbeitstage, tag] : this.arbeitstage.filter(t => t !== tag); }
  async laden(): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set(''); this.konto.set(null);
    try {
      const k = await firstValueFrom(mitFehlern(this.http.get<Urlaubskonto>(this.pfad())));
      if (this.darfSchreiben()) this.kalender.set(await firstValueFrom(mitFehlern(this.http.get<Feiertagskalender[]>('/api/feiertagskalender', { params: { jahr: k.jahr } }))));
      this.uebernehmen(k);
    } catch (e) { this.fehler.set(this.text(e)); } finally { this.arbeitet.set(false); }
  }
  async speichern(): Promise<void> {
    if (this.arbeitet() || !this.konto() || this.jahr !== this.konto()!.jahr) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try {
      const feiertage = [...new Set(this.feiertage.split(/[\s,;]+/).filter(Boolean))];
      if (feiertage.some(d => !/^\d{4}-\d{2}-\d{2}$/.test(d))) throw new Error('Feiertage als JJJJ-MM-TT eingeben, je Datum eine Zeile.');
      const ausnahmen = this.kalenderId === null ? [] : [...new Set(this.ausnahmen.split(/[\s,;]+/).filter(Boolean))];
      if (ausnahmen.some(d => !/^\d{4}-\d{2}-\d{2}$/.test(d))) throw new Error('Ausnahmen als JJJJ-MM-TT eingeben.');
      const k = await firstValueFrom(mitFehlern(this.http.put<Urlaubskonto>(this.pfad(), { anspruch: this.anspruch, uebertrag: this.uebertrag, arbeitstage: this.arbeitstage, feiertage, version: this.konto()!.version, feiertagskalenderId: this.kalenderId, ausnahmen })));
      this.uebernehmen(k); this.meldung.set('Urlaubskonto gespeichert.');
    } catch (e) { this.fehler.set(this.text(e)); } finally { this.arbeitet.set(false); }
  }
  async vorjahrUebernehmen(): Promise<void> {
    if (this.arbeitet() || this.jahr <= 1) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try {
      const k = await firstValueFrom(mitFehlern(this.http.get<Urlaubskonto>(`/api/mitarbeiter/${this.person()}/urlaubskonto/${this.jahr - 1}`)));
      if (!k.eingerichtet) throw new Error('Für das Vorjahr ist kein Urlaubskonto eingerichtet. Den bestätigten Übertrag bitte manuell eingeben.');
      if (k.beantragt > 0) throw new Error('Im Vorjahr sind noch Anträge offen. Bitte zuerst entscheiden oder zurückziehen.');
      this.uebertrag = Math.max(0, k.rest);
      this.meldung.set('Resturlaub aus dem Vorjahr in das Übertragsfeld übernommen. Bitte prüfen und das Urlaubskonto speichern. Spätere Änderungen im Vorjahr müssen erneut abgeglichen werden.');
    } catch (e) { this.fehler.set(this.text(e)); } finally { this.arbeitet.set(false); }
  }
  private uebernehmen(k: Urlaubskonto): void { this.konto.set(k); this.anspruch = k.anspruch; this.uebertrag = k.uebertrag; this.arbeitstage = k.eingerichtet ? k.arbeitstage : [1, 2, 3, 4, 5]; this.feiertage = k.feiertage.join('\n'); this.kalenderId = k.feiertagskalenderId; this.ausnahmen = k.ausnahmen.join('\n'); }
  private pfad(): string { return `/api/mitarbeiter/${this.person()}/urlaubskonto/${this.jahr}`; }
  private text(e: unknown): string { return e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Urlaubskonto konnte nicht geladen werden.'; }
}
