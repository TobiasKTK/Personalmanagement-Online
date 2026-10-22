import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
interface Vertretung { id: number; genehmiger: string; vertreter: string; beginn: string; ende: string; version: number; }
@Component({ selector: 'app-vertretung', standalone: true, imports: [FormsModule, DatePipe], template: `
  <section aria-label="Genehmigervertretungen"><h2>Genehmigervertretungen</h2><p>Die Vertretung erhält zusätzlich zum Genehmiger Freigaberechte im angegebenen Zeitraum. Keine Weiterdelegation und keine Selbstgenehmigung.</p>
    @if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> } @if (meldung()) { <p role="status">{{ meldung() }}</p> }
    <form (ngSubmit)="speichern()"><fieldset [disabled]="arbeitet()"><legend>{{ bearbeitet ? 'Vertretung bearbeiten' : 'Neue Vertretung' }}</legend><div class="filter-grid">
      <label class="field">Genehmiger<select name="genehmiger" [(ngModel)]="eingabe.genehmiger" required><option value="">Bitte wählen</option>@for (k of konten(); track k) { <option [value]="k">{{ k }}</option> }</select></label>
      <label class="field">Vertreter<select name="vertreter" [(ngModel)]="eingabe.vertreter" required><option value="">Bitte wählen</option>@for (k of konten(); track k) { <option [value]="k">{{ k }}</option> }</select></label>
      <label class="field">Vertretung ab<input type="date" name="beginn" [(ngModel)]="eingabe.beginn" required></label>
      <label class="field">Vertretung bis<input type="date" name="ende" [(ngModel)]="eingabe.ende" [min]="eingabe.beginn" required></label>
      <button class="button primary">Vertretung speichern</button><button type="button" class="button secondary" (click)="bearbeiten()">Zurücksetzen</button>
    </div></fieldset></form>
    <ul class="management-list">@for (v of eintraege(); track v.id) { <li><div>{{ v.genehmiger }} → {{ v.vertreter }}<p>{{ v.beginn | date:'dd.MM.yyyy' }} – {{ v.ende | date:'dd.MM.yyyy' }}</p></div><div class="actions">
      <button class="button secondary compact" [disabled]="arbeitet()" (click)="bearbeiten(v)">Bearbeiten</button><button class="button secondary compact" [disabled]="arbeitet()" (click)="loeschen(v)">Vertretung entfernen</button></div></li>
    } @empty { <li>Keine Vertretungen eingerichtet.</li> }</ul>
  </section>` })
export class VertretungComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly konten = signal<string[]>([]); readonly eintraege = signal<Vertretung[]>([]);
  readonly fehler = signal(''); readonly meldung = signal(''); readonly arbeitet = signal(false);
  bearbeitet: Vertretung | null = null;
  eingabe = { genehmiger: '', vertreter: '', beginn: '', ende: '' };
  ngOnInit(): void { void this.ausfuehren(async () => {
    const k = await firstValueFrom(mitFehlern(this.http.get<{ name: string; rolle: string; aktiv: boolean }[]>('/api/konten')));
    this.konten.set(k.filter(k => k.aktiv && k.rolle !== 'LESEN').map(k => k.name)); await this.laden();
  }); }
  bearbeiten(v?: Vertretung): void { this.bearbeitet = v ?? null; this.eingabe = v ? { genehmiger: v.genehmiger, vertreter: v.vertreter, beginn: v.beginn, ende: v.ende } : { genehmiger: '', vertreter: '', beginn: '', ende: '' }; }
  async speichern(): Promise<void> { await this.ausfuehren(async () => {
    const e = { ...this.eingabe, version: this.bearbeitet?.version };
    await firstValueFrom(mitFehlern(this.bearbeitet ? this.http.put('/api/vertretungen/' + this.bearbeitet.id, e) : this.http.post('/api/vertretungen', e)));
    this.bearbeiten(); await this.laden(); this.meldung.set('Vertretung gespeichert.');
  }); }
  async loeschen(v: Vertretung): Promise<void> { await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.delete('/api/vertretungen/' + v.id, { params: { version: v.version } }))); this.bearbeiten(); await this.laden(); this.meldung.set('Vertretung entfernt.'); }); }
  private async laden(): Promise<void> { this.eintraege.set(await firstValueFrom(mitFehlern(this.http.get<Vertretung[]>('/api/vertretungen')))); }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> { if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set(''); try { await aktion(); } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Aktion fehlgeschlagen.'); } finally { this.arbeitet.set(false); } }
}
