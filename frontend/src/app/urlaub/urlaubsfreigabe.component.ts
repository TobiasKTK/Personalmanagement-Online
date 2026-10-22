import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { mitFehlern, ApiFehler } from '../shared/api-fehler';
import { Konto } from '../shared/model';
export interface UrlaubZuordnung { mitarbeiterId: number; konto: string | null; modus: 'ALLE' | 'ZUGEORDNET'; genehmiger: string | null; version: number | null; }
@Component({ selector: 'app-urlaubsfreigabe', standalone: true, imports: [FormsModule], template: `
  <section aria-label="Urlaubsfreigabe einrichten"><h3>Benutzerkonto und Urlaubsfreigabe</h3>
    @if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> }
    @if (meldung()) { <p role="status">{{ meldung() }}</p> }
    @if (zuordnung(); as z) {
      <form (ngSubmit)="speichern()"><fieldset [disabled]="arbeitet()"><legend>Zuordnung für diesen Mitarbeiter</legend>
        <label class="field">Mitarbeiterkonto<select name="konto" [(ngModel)]="z.konto"><option [ngValue]="null">Kein Konto</option>@for (k of konten(); track k.name) { <option [ngValue]="k.name" [disabled]="!k.aktiv">{{ k.name }}{{ !k.aktiv ? ' (gesperrt)' : '' }}</option> }</select></label>
        <label class="field">Genehmigungsmodus<select name="modus" [(ngModel)]="z.modus" (ngModelChange)="z.genehmiger = null"><option value="ALLE">Personalverwaltung, Administration und zuständige Teamleitungen</option><option value="ZUGEORDNET">Zugeordneter Genehmiger</option></select></label>
        @if (z.modus === 'ZUGEORDNET') { <label class="field">Genehmiger<select name="genehmiger" [(ngModel)]="z.genehmiger" required><option [ngValue]="null">Bitte wählen</option>@for (k of konten(); track k.name) { @if (k.rolle === 'PERSONAL' || k.rolle === 'ADMIN' || k.rolle === 'TEAMLEITUNG') { <option [ngValue]="k.name" [disabled]="!k.aktiv || k.name === z.konto">{{ k.name }}</option> } }</select></label> }
        <p>Eigene Anträge können nicht selbst genehmigt werden. Geänderte Zuständigkeiten gelten auch für offene Anträge.</p>
        <button class="button primary">Urlaubsfreigabe speichern</button>
      </fieldset></form>
    }
  </section>` })
export class UrlaubsfreigabeComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly person = input.required<number>();
  readonly zuordnung = signal<UrlaubZuordnung | null>(null);
  readonly konten = signal<Konto[]>([]);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly arbeitet = signal(false);
  async ngOnInit(): Promise<void> {
    try { const [z, k] = await Promise.all([firstValueFrom(mitFehlern(this.http.get<UrlaubZuordnung>(this.pfad()))), firstValueFrom(mitFehlern(this.http.get<Konto[]>('/api/konten')))]); this.zuordnung.set(z); this.konten.set(k); }
    catch (e) { this.fehler.set(this.text(e)); }
  }
  async speichern(): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { const z = this.zuordnung()!; this.zuordnung.set(await firstValueFrom(mitFehlern(this.http.put<UrlaubZuordnung>(this.pfad(), { konto: z.konto, modus: z.modus, genehmiger: z.genehmiger, version: z.version })))); this.meldung.set('Urlaubsfreigabe gespeichert.'); }
    catch (e) { this.fehler.set(this.text(e)); } finally { this.arbeitet.set(false); }
  }
  private pfad(): string { return `/api/mitarbeiter/${this.person()}/urlaubsfreigabe`; }
  private text(e: unknown): string { return e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Zuordnung fehlgeschlagen.'; }
}
