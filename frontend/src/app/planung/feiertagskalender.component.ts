import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
import { heutigesDatum } from '../shared/model';
export interface Feiertagskalender { id: number; name: string; jahr: number; feiertage: string[]; version: number; }
@Component({ selector: 'app-feiertagskalender', standalone: true, imports: [FormsModule], template: `
<h2>Zentrale Feiertagskalender</h2><p>Feiertage je Standort und Jahr pflegen. Die Zuordnung erfolgt im Urlaubskonto. Änderungen berechnen alle zugeordneten Konten neu.</p>
@if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> } @if (meldung()) { <p role="status">{{ meldung() }}</p> }
<form (ngSubmit)="speichern()"><fieldset [disabled]="arbeitet()"><legend>{{ id === null ? 'Kalender anlegen' : 'Kalender bearbeiten' }}</legend><div class="filter-grid">
<label class="field">Standort / Kalendername<input name="kalendername" [(ngModel)]="name" required maxlength="120"></label>
<label class="field">Kalenderjahr<input type="number" name="kalenderjahr" [(ngModel)]="jahr" min="1" max="9999" required></label>
<label class="field">Feiertage (JJJJ-MM-TT, je Zeile)<textarea name="kalendertage" [(ngModel)]="tage" rows="5"></textarea></label>
<button class="button primary">Kalender speichern</button><button type="button" class="button secondary" (click)="neu()">Neuer Kalender</button>
</div></fieldset></form>
<button class="button secondary" [disabled]="arbeitet()" (click)="laden()">Kalender aktualisieren</button>
<ul class="management-list">@for (k of kalender(); track k.id) { <li><div><strong>{{ k.name }} · {{ k.jahr }}</strong><p>{{ k.feiertage.length }} Feiertage</p></div><div class="actions"><button class="button secondary" [disabled]="arbeitet()" (click)="bearbeiten(k)">Bearbeiten</button><button class="button secondary" [disabled]="arbeitet()" (click)="loeschen(k)">Löschen</button></div></li> } @empty { <li>Noch keine zentralen Kalender eingerichtet.</li> }</ul>` })
export class FeiertagskalenderComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly kalender = signal<Feiertagskalender[]>([]); readonly arbeitet = signal(false); readonly fehler = signal(''); readonly meldung = signal('');
  id: number | null = null; version: number | null = null; name = ''; jahr = +heutigesDatum().slice(0, 4); tage = '';
  ngOnInit(): void { void this.laden(); }
  neu(): void { this.id = null; this.version = null; this.name = ''; this.tage = ''; }
  bearbeiten(k: Feiertagskalender): void { this.id = k.id; this.version = k.version; this.name = k.name; this.jahr = k.jahr; this.tage = k.feiertage.join('\n'); }
  async laden(): Promise<void> { await this.ausfuehren(() => this.daten()); }
  private async daten(): Promise<void> { this.kalender.set(await firstValueFrom(mitFehlern(this.http.get<Feiertagskalender[]>('/api/feiertagskalender')))); }
  async speichern(): Promise<void> { await this.ausfuehren(async () => {
    const feiertage = [...new Set(this.tage.split(/[\s,;]+/).filter(Boolean))];
    if (feiertage.some(d => !/^\d{4}-\d{2}-\d{2}$/.test(d))) throw new Error('Feiertage als JJJJ-MM-TT eingeben.');
    const body = { name: this.name, jahr: this.jahr, feiertage, version: this.version };
    await firstValueFrom(mitFehlern(this.id === null ? this.http.post('/api/feiertagskalender', body) : this.http.put('/api/feiertagskalender/' + this.id, body)));
    this.neu(); await this.daten(); this.meldung.set('Feiertagskalender gespeichert.');
  }); }
  async loeschen(k: Feiertagskalender): Promise<void> { if (!confirm(`Kalender „${k.name} ${k.jahr}“ löschen?`)) return; await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.delete('/api/feiertagskalender/' + k.id, { params: { version: k.version } }))); if (this.id === k.id) this.neu(); await this.daten(); this.meldung.set('Kalender gelöscht.'); }); }
  private async ausfuehren(a: () => Promise<void>): Promise<void> { if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set(''); try { await a(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Anfrage fehlgeschlagen.'); } finally { this.arbeitet.set(false); } }
}
