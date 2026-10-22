import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
interface Speicher { mandantenspeicherId: number; name: string; status: string; version: number; }
@Component({ selector: 'app-mandantenverwaltung', standalone: true, imports: [FormsModule, RouterLink], template: `
<section class="team-panel administration"><h2>Firmen verwalten</h2>
<p>Jede Firma besitzt einen eigenen Datenbestand in der gemeinsamen Datenbank. Kontonamen sind über alle Firmen hinweg eindeutig.</p>
@if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> }
@if (meldung()) { <p role="status">{{ meldung() }}</p> }
<form (ngSubmit)="firmaSpeichern()"><fieldset [disabled]="arbeitet()"><legend>{{ bearbeitet ? 'Firma umbenennen' : 'Firma anlegen' }}</legend>
<label class="field">Firmenname<input name="firmenname" [(ngModel)]="firmenname" required maxlength="120"></label>
<button class="button primary">{{ bearbeitet ? 'Firmenname speichern' : 'Firma anlegen' }}</button>
@if (bearbeitet) { <button type="button" class="button secondary" (click)="bearbeitet = null; firmenname = ''">Abbrechen</button> }
</fieldset></form>
<ul class="management-list">@for (m of speicher(); track m.mandantenspeicherId) {
<li><div><strong>{{ m.name }}</strong><p>Mandantenspeicher-ID {{ m.mandantenspeicherId }} · {{ m.status === 'AKTIV' ? 'bereit' : 'Einrichtung unvollständig' }}</p></div>
<button class="button secondary" [disabled]="arbeitet()" (click)="bearbeitet = m; firmenname = m.name">{{ m.name }} umbenennen</button>
@if (m.status !== 'AKTIV') { <button class="button secondary" [disabled]="arbeitet()" (click)="einrichten(m)">Einrichtung fortsetzen</button> }</li>
}</ul>
<p>Benutzerkonten, Rollen und Firmenzuordnungen verwaltest du unter <a routerLink="/administration/konten">Benutzer &amp; Zugänge</a>.</p>
</section>` })
export class MandantenverwaltungComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly speicher = signal<Speicher[]>([]);
  readonly fehler = signal(''); readonly meldung = signal(''); readonly arbeitet = signal(false);
  firmenname = ''; bearbeitet: Speicher | null = null;
  ngOnInit(): void { void this.ausfuehren(() => this.laden()); }
  private async laden(): Promise<void> {
    this.speicher.set(await firstValueFrom(mitFehlern(this.http.get<Speicher[]>('/api/mandantenverwaltung'))));
  }
  async firmaSpeichern(): Promise<void> { await this.ausfuehren(async () => {
    const body = { name: this.firmenname.trim(), version: this.bearbeitet?.version ?? null };
    await firstValueFrom(mitFehlern(this.bearbeitet ? this.http.put('/api/mandantenverwaltung/' + this.bearbeitet.mandantenspeicherId, body) : this.http.post('/api/mandantenverwaltung', body)));
    this.bearbeitet = null; this.firmenname = ''; await this.laden(); this.meldung.set('Firma gespeichert.');
  }); }
  async einrichten(m: Speicher): Promise<void> { await this.ausfuehren(async () => {
    await firstValueFrom(mitFehlern(this.http.post('/api/mandantenverwaltung/' + m.mandantenspeicherId + '/einrichten', {})));
    await this.laden(); this.meldung.set('Mandantenspeicher ist bereit.');
  }); }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Anfrage fehlgeschlagen.'); }
    finally { this.arbeitet.set(false); }
  }
}
