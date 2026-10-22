import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Konto, Organisation } from '../shared/model';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
interface Zuordnung { organisationseinheiten: number[]; version: number; }
@Component({ selector: 'app-teamzuordnung', standalone: true, imports: [FormsModule], template: `
  <section aria-labelledby="teamzustandigkeit-titel">
    <h2 id="teamzustandigkeit-titel">Teamzuständigkeiten</h2>
    <p>Für die aktuelle Firma: Wähle eine Teamleitung und ihre Organisationseinheiten. Untereinheiten werden einzeln zugeordnet. Ohne Auswahl besteht kein Teamzugriff.</p>
    @if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> }
    @if (meldung()) { <p role="status">{{ meldung() }}</p> }
    <button class="button secondary" type="button" [disabled]="arbeitet()" (click)="laden()">Teamleitungen aktualisieren</button>
    <label class="field">Teamleitung auswählen<select [ngModel]="konto" (ngModelChange)="waehlen($event)" [disabled]="arbeitet()">
      <option value="">Bitte wählen</option>@for (k of konten(); track k.name) { <option [value]="k.name">{{ k.name }}{{ k.aktiv ? '' : ' (gesperrt)' }}</option> }
    </select></label>
    @if (!arbeitet() && !konten().length) { <p>Noch kein Konto mit der Rolle Teamleitung in dieser Firma vorhanden.</p> }
    @if (zuordnung(); as z) {
      <form (ngSubmit)="speichern()"><fieldset [disabled]="arbeitet()"><legend>Organisationseinheiten für {{ konto }}</legend>
        @for (o of organisationen(); track o.id) { <label class="field"><span><input type="checkbox" [name]="'team-org-' + o.id" [checked]="z.organisationseinheiten.includes(o.id)" (change)="umschalten(o.id)"> {{ o.name }}</span></label> }
        @empty { <p>Noch keine Organisationseinheiten vorhanden.</p> }
        <button class="button primary">Teamzuständigkeit speichern</button>
      </fieldset></form>
    }
  </section>` })
export class TeamzuordnungComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly konten = signal<Konto[]>([]);
  readonly organisationen = signal<Organisation[]>([]);
  readonly zuordnung = signal<Zuordnung | null>(null);
  readonly arbeitet = signal(false);
  readonly fehler = signal('');
  readonly meldung = signal('');
  konto = '';
  ngOnInit(): void { void this.laden(); }
  async laden(): Promise<void> {
    await this.ausfuehren(async () => {
      this.zuordnung.set(null); this.konto = '';
      const [k, o] = await Promise.all([firstValueFrom(mitFehlern(this.http.get<Konto[]>('/api/konten'))), firstValueFrom(mitFehlern(this.http.get<Organisation[]>('/api/organisationseinheiten')))]);
      this.konten.set(k.filter(e => e.rolle === 'TEAMLEITUNG')); this.organisationen.set(o);
    });
  }
  async waehlen(konto: string): Promise<void> {
    await this.ausfuehren(async () => {
      this.konto = konto; this.zuordnung.set(null);
      if (konto) this.zuordnung.set(await firstValueFrom(mitFehlern(this.http.get<Zuordnung>(this.pfad()))));
    });
  }
  umschalten(id: number): void {
    this.zuordnung.update(z => z ? { ...z, organisationseinheiten: z.organisationseinheiten.includes(id) ? z.organisationseinheiten.filter(o => o !== id) : [...z.organisationseinheiten, id] } : null);
  }
  async speichern(): Promise<void> {
    await this.ausfuehren(async () => {
      this.zuordnung.set(await firstValueFrom(mitFehlern(this.http.put<Zuordnung>(this.pfad(), this.zuordnung()))));
      this.meldung.set('Teamzuständigkeit gespeichert. Die Änderung gilt ab der nächsten Anfrage.');
    });
  }
  private pfad(): string { return '/api/konten/' + encodeURIComponent(this.konto) + '/team'; }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : 'Teamzuständigkeit konnte nicht geladen oder gespeichert werden.'); }
    finally { this.arbeitet.set(false); }
  }
}
