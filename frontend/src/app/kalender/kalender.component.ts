import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Abwesenheit, Mitarbeiter, Organisation, heutigesDatum } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';
import { OrganisationApiService } from '../organisation/organisation-api.service';
import { SitzungService } from '../sitzung/sitzung.service';
import { MitarbeiterDetailsComponent } from '../abwesenheit/mitarbeiter-details.component';

interface KalenderPerson { id: number; name: string; personalnummer: number; organisationseinheit: string; historieBekannt: boolean; abwesenheiten: Abwesenheit[]; }
interface KalenderSeite { beginn: string; ende: string; eintraege: KalenderPerson[]; gesamt: number; }
@Component({ selector: 'app-kalender', standalone: true, imports: [FormsModule, MitarbeiterDetailsComponent], templateUrl: './kalender.component.html', styleUrl: './kalender.component.css' })
export class KalenderComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly orgApi = inject(OrganisationApiService);
  readonly sitzung = inject(SitzungService).sitzung;
  readonly daten = signal<KalenderSeite | null>(null);
  readonly organisationen = signal<Organisation[]>([]);
  readonly details = signal<Mitarbeiter | null>(null);
  readonly fehler = signal('');
  readonly laedt = signal(false);
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
  async laden(neu = false): Promise<void> {
    clearTimeout(this.suchTimer);
    if (this.destroyRef.destroyed) return;
    if (neu) this.seite = 0;
    const nr = ++this.anfrage;
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(this.monat) || this.monat.startsWith('0000')) { this.fehler.set('Bitte einen gültigen Monat wählen.'); this.daten.set(null); this.laedt.set(false); return; }
    this.laedt.set(true); this.fehler.set('');
    try {
      const params: Record<string, string | number> = { jahr: +this.monat.slice(0, 4), monat: +this.monat.slice(5), seite: this.seite, suche: this.suche.trim() };
      if (this.organisation) params['organisationseinheitId'] = this.organisation;
      const daten = await firstValueFrom(mitFehlern(this.http.get<KalenderSeite>('/api/kalender', { params })));
      if (nr === this.anfrage) this.daten.set(daten);
    } catch (e) { if (nr === this.anfrage) this.fehler.set(this.text(e)); }
    finally { if (nr === this.anfrage) this.laedt.set(false); }
  }
  suchen(text: string): void {
    this.suche = text;
    ++this.anfrage;
    clearTimeout(this.suchTimer);
    this.laedt.set(true);
    this.suchTimer = setTimeout(() => { void this.laden(true); }, 300);
  }
  tage(): string[] { const d = this.daten(); return d ? Array.from({ length: +d.ende.slice(8) }, (_, i) => d.beginn.slice(0, 8) + String(i + 1).padStart(2, '0')) : []; }
  wochenende(tag: string): boolean { return [0, 6].includes(new Date(tag + 'T12:00:00Z').getUTCDay()); }
  art(p: KalenderPerson, tag: string): string { return p.abwesenheiten.find(a => a.beginn <= tag && a.ende >= tag)?.art ?? ''; }
  halb(p: KalenderPerson, tag: string): boolean { return p.abwesenheiten.some(a => a.beginn <= tag && a.ende >= tag && a.halberTag); }
  async oeffnen(id: number): Promise<void> {
    try { this.details.set(await firstValueFrom(mitFehlern(this.http.get<Mitarbeiter>(`/api/mitarbeiter/${id}`)))); }
    catch (e) { this.fehler.set(this.text(e)); }
  }
  private text(e: unknown): string { return e instanceof Error ? e.message : 'Kalender konnte nicht geladen werden.'; }
}
