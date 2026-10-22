import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
interface Regel { organisationseinheitId: number; organisation: string; mindestbesetzung: number; wochentage: number[]; version: number; }
@Component({ selector: 'app-besetzung', standalone: true, imports: [FormsModule], template: `
<h2>Mindestbesetzung</h2><p>Warnung vor Urlaubsfreigaben je Organisationseinheit. Eine Person zählt pro Arbeitstag als 1, ein halber Abwesenheitstag als 0,5. Die Warnung kann bewusst bestätigt werden.</p>
@if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> } @if (meldung()) { <p role="status">{{ meldung() }}</p> }
<form (ngSubmit)="speichern()"><fieldset [disabled]="arbeitet()"><legend>Besetzungsregel</legend><div class="filter-grid">
<label class="field">Organisation für Mindestbesetzung<select name="besetzungOrganisation" [(ngModel)]="organisation" (ngModelChange)="waehlen()" required><option [ngValue]="null">Bitte wählen</option>@for (o of organisationen(); track o.id) { <option [ngValue]="o.id">{{ o.name }}</option> }</select></label>
<label class="field">Mindestens verfügbare Personen<input type="number" name="minimum" [(ngModel)]="minimum" min="0" max="9999" step="0.5" required></label>
<div><p>Prüftage</p>@for (tag of tage; track tag; let i = $index) { <label><input type="checkbox" [name]="'prueftag' + i" [ngModel]="wochentage.includes(i + 1)" (ngModelChange)="tagWaehlen(i + 1, $event)">{{ tag }}</label> }</div>
<button class="button primary" [disabled]="organisation === null || !wochentage.length">Besetzungsregel speichern</button>
</div></fieldset></form><button class="button secondary" [disabled]="arbeitet()" (click)="laden()">Regeln aktualisieren</button>
<ul class="management-list">@for (r of regeln(); track r.organisationseinheitId) { <li><div><strong>{{ r.organisation }}</strong><p>Mindestens {{ r.mindestbesetzung }} Personen · {{ r.wochentage.length }} Prüftage pro Woche</p></div><div class="actions"><button class="button secondary" [disabled]="arbeitet()" (click)="organisation = r.organisationseinheitId; waehlen()">Bearbeiten</button><button class="button secondary" [disabled]="arbeitet()" (click)="loeschen(r)">Regel entfernen</button></div></li> } @empty { <li>Noch keine Mindestbesetzung eingerichtet.</li> }</ul>` })
export class BesetzungComponent implements OnInit {
  private readonly http = inject(HttpClient); readonly regeln = signal<Regel[]>([]); readonly organisationen = signal<{id: number; name: string}[]>([]);
  readonly fehler = signal(''); readonly meldung = signal(''); readonly arbeitet = signal(false); readonly tage = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
  organisation: number | null = null; version: number | null = null; minimum = 1; wochentage = [1, 2, 3, 4, 5];
  ngOnInit(): void { void this.laden(); }
  waehlen(): void { const r = this.regeln().find(r => r.organisationseinheitId === this.organisation); this.version = r?.version ?? null; this.minimum = r?.mindestbesetzung ?? 1; this.wochentage = r ? [...r.wochentage] : [1, 2, 3, 4, 5]; }
  tagWaehlen(tag: number, aktiv: boolean): void { this.wochentage = aktiv ? [...this.wochentage, tag] : this.wochentage.filter(t => t !== tag); }
  async laden(): Promise<void> { await this.ausfuehren(async () => { const [r, o] = await Promise.all([firstValueFrom(mitFehlern(this.http.get<Regel[]>('/api/besetzungsregeln'))), firstValueFrom(mitFehlern(this.http.get<{id: number; name: string}[]>('/api/organisationseinheiten')))]); this.regeln.set(r); this.organisationen.set(o); this.waehlen(); }); }
  private async daten(): Promise<void> { this.regeln.set(await firstValueFrom(mitFehlern(this.http.get<Regel[]>('/api/besetzungsregeln')))); this.waehlen(); }
  async speichern(): Promise<void> { if (this.organisation === null) return; await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.put('/api/besetzungsregeln/' + this.organisation, { mindestbesetzung: this.minimum, wochentage: this.wochentage, version: this.version }))); await this.daten(); this.meldung.set('Besetzungsregel gespeichert.'); }); }
  async loeschen(r: Regel): Promise<void> { if (!confirm(`Besetzungsregel für „${r.organisation}“ entfernen?`)) return; await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.delete('/api/besetzungsregeln/' + r.organisationseinheitId, { params: { version: r.version } }))); await this.daten(); this.meldung.set('Besetzungsregel entfernt.'); }); }
  private async ausfuehren(a: () => Promise<void>): Promise<void> { if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set(''); try { await a(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Anfrage fehlgeschlagen.'); } finally { this.arbeitet.set(false); } }
}
