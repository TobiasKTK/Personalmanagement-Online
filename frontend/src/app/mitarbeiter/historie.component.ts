import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { mitFehlern, ApiFehler } from '../shared/api-fehler';
import { Organisation, heutigesDatum } from '../shared/model';
interface Historie { id: number; gueltigAb: string; organisationseinheitId: number | null; organisationseinheit: string; wochenstunden: number; version: number; }
@Component({ selector: 'app-historie', standalone: true, imports: [FormsModule, DatePipe], templateUrl: './historie.component.html' })
export class HistorieComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly person = input.required<number>();
  readonly eintraege = signal<Historie[]>([]);
  readonly organisationen = signal<Organisation[]>([]);
  readonly gesamt = signal(0);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly arbeitet = signal(false);
  readonly geladen = signal(false);
  seite = 0;
  mitarbeiterVersion = 0;
  bearbeitet: Historie | null = null;
  eingabe = { gueltigAb: heutigesDatum(), organisationseinheitId: null as number | null, wochenstunden: 40 };
  ngOnInit(): void { void this.laden(); }
  bearbeiten(h?: Historie): void { this.bearbeitet = h ?? null; this.eingabe = h ? { gueltigAb: h.gueltigAb, organisationseinheitId: h.organisationseinheitId, wochenstunden: h.wochenstunden } : { gueltigAb: heutigesDatum(), organisationseinheitId: null, wochenstunden: 40 }; }
  async laden(): Promise<void> { await this.ausfuehren(() => this.datenLaden()); }
  private async datenLaden(): Promise<void> {
    const [h, org] = await Promise.all([
      firstValueFrom(mitFehlern(this.http.get<{ eintraege: Historie[]; gesamt: number; mitarbeiterVersion: number }>(this.pfad(), { params: { seite: this.seite } }))),
      firstValueFrom(mitFehlern(this.http.get<Organisation[]>('/api/organisationseinheiten'))),
    ]);
    this.eintraege.set(h.eintraege); this.gesamt.set(h.gesamt); this.mitarbeiterVersion = h.mitarbeiterVersion; this.organisationen.set(org); this.geladen.set(true);
    if (!h.eintraege.length && this.seite > 0) { this.seite--; await this.datenLaden(); }
  }
  async speichern(): Promise<void> {
    await this.ausfuehren(async () => {
      const e = { ...this.eingabe, version: this.bearbeitet?.version, mitarbeiterVersion: this.mitarbeiterVersion };
      await firstValueFrom(mitFehlern(this.bearbeitet ? this.http.put(this.pfad() + '/' + this.bearbeitet.id, e) : this.http.post(this.pfad(), e)));
      this.bearbeiten(); this.seite = 0; await this.datenLaden(); this.meldung.set('Historischer Stand gespeichert.');
    });
  }
  async loeschen(h: Historie): Promise<void> {
    if (!confirm('Diesen Stand entfernen? Der vorherige Stand gilt dann bis zum nächsten Eintrag weiter.')) return;
    await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.delete(this.pfad() + '/' + h.id, { params: { version: h.version, mitarbeiterVersion: this.mitarbeiterVersion } }))); this.bearbeiten(); await this.datenLaden(); this.meldung.set('Historischer Stand entfernt.'); });
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Historie konnte nicht verarbeitet werden.'); }
    finally { this.arbeitet.set(false); }
  }
  private pfad(): string { return `/api/mitarbeiter/${this.person()}/historie`; }
}
