import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
import { SitzungService } from '../sitzung/sitzung.service';
interface Speicher { mandantenspeicherId: number; name: string; status: string; version: number; }
interface Mitgliedschaft { mandantenspeicherId: number; firma: string; rolle: string; aktiv: boolean; }
interface Konto { name: string; aktiv: boolean; systemverwaltung: boolean; zuordnungen: Mitgliedschaft[]; }
@Component({ selector: 'app-mandantenverwaltung', standalone: true, imports: [FormsModule], template: `
<section class="team-panel administration"><h1>Firmen und Mandantenspeicher</h1>
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
<h2>Firmenzugang zuordnen</h2><p>Neue Konten werden unter „Konten“ angelegt. Ein bestehendes Konto kann hier weiteren Firmen zugeordnet werden.</p>
<form (ngSubmit)="zuordnen()"><fieldset [disabled]="arbeitet()"><legend>Konto und Firmenrechte</legend><div class="filter-grid">
<label class="field">Konto für Firmenzugang<select name="zugangKonto" [(ngModel)]="zugangName" required><option value="">Bitte wählen</option>@for (k of konten(); track k.name) { <option [value]="k.name">{{ k.name }}</option> }</select></label>
<label class="field">Firma für Zugang<select name="zugangFirma" [(ngModel)]="zugangId" required><option [ngValue]="null">Bitte wählen</option>@for (m of speicher(); track m.mandantenspeicherId) { @if (m.status === 'AKTIV') { <option [ngValue]="m.mandantenspeicherId">{{ m.name }} ({{ m.mandantenspeicherId }})</option> } }</select></label>
<label class="field">Rolle in dieser Firma<select name="zugangRolle" [(ngModel)]="zugangRolle"><option value="LESEN">Lesen</option><option value="PERSONAL">Personalverwaltung</option><option value="ADMIN">Administration</option></select></label>
<label><input type="checkbox" name="zugangAktiv" [(ngModel)]="zugangAktiv"> Zugang aktiv</label>
<button class="button primary" [disabled]="!zugangName || !zugangId">Firmenzugang speichern</button></div></fieldset></form>
<h2>Zentrale Konten</h2><p>Ein Passwort gilt für alle zugeordneten Firmen. Die Systemverwaltung verwaltet Firmen und Zuordnungen; fachliche Daten bleiben an die Firmenrechte gebunden.</p>
<ul class="management-list">@for (k of konten(); track k.name) {
<li><div><strong>{{ k.name }}</strong><p>{{ k.aktiv ? 'Konto aktiv' : 'Konto gesperrt' }}{{ k.systemverwaltung ? ' · Systemverwaltung' : '' }}</p>
@for (z of k.zuordnungen; track z.mandantenspeicherId) {
<p>{{ z.firma }} ({{ z.mandantenspeicherId }}) · {{ z.rolle }} · {{ z.aktiv ? 'aktiv' : 'gesperrt' }}
<button class="button secondary compact" [disabled]="arbeitet()" (click)="zugangBearbeiten(k.name, z)">Zugang {{ k.name }} / {{ z.firma }} bearbeiten</button></p>
}</div><button class="button secondary" [disabled]="arbeitet()" (click)="kontoBearbeiten(k)">Globales Konto {{ k.name }} bearbeiten</button></li>
}</ul>
@if (kontoName) {
<form (ngSubmit)="kontoSpeichern()"><fieldset [disabled]="arbeitet()"><legend>Globales Konto {{ kontoName }}</legend>
<label><input type="checkbox" name="kontoAktiv" [(ngModel)]="kontoAktiv"> Konto insgesamt aktiv</label>
<label><input type="checkbox" name="systemverwaltung" [(ngModel)]="systemverwaltung"> Systemverwaltung</label>
@if (!session.sitzung()?.keycloak) {
<label class="field">Neues globales Passwort (leer: beibehalten)<input type="password" name="globalesPasswort" [(ngModel)]="passwort" minlength="12" maxlength="128" autocomplete="new-password"></label>
} @else { <p>Passwörter und E-Mail-Adressen werden in Keycloak verwaltet.</p> }
<button class="button primary">Globales Konto speichern</button><button type="button" class="button secondary" (click)="kontoName = ''; passwort = ''">Abbrechen</button>
</fieldset></form>
}</section>` })
export class MandantenverwaltungComponent implements OnInit {
  readonly session = inject(SitzungService);
  private readonly http = inject(HttpClient);
  readonly speicher = signal<Speicher[]>([]); readonly konten = signal<Konto[]>([]);
  readonly fehler = signal(''); readonly meldung = signal(''); readonly arbeitet = signal(false);
  firmenname = ''; bearbeitet: Speicher | null = null;
  zugangName = ''; zugangId: number | null = null; zugangRolle = 'LESEN'; zugangAktiv = true;
  kontoName = ''; kontoAktiv = true; systemverwaltung = false; passwort = '';
  ngOnInit(): void { void this.ausfuehren(() => this.laden()); }
  private async laden(): Promise<void> {
    const [m, k] = await Promise.all([firstValueFrom(mitFehlern(this.http.get<Speicher[]>('/api/mandantenverwaltung'))), firstValueFrom(mitFehlern(this.http.get<Konto[]>('/api/mandantenverwaltung/konten')))]);
    this.speicher.set(m); this.konten.set(k);
  }
  async firmaSpeichern(): Promise<void> { await this.ausfuehren(async () => {
    const body = { name: this.firmenname.trim(), version: this.bearbeitet?.version ?? null };
    await firstValueFrom(mitFehlern(this.bearbeitet ? this.http.put('/api/mandantenverwaltung/' + this.bearbeitet.mandantenspeicherId, body) : this.http.post('/api/mandantenverwaltung', body)));
    this.bearbeitet = null; this.firmenname = ''; await this.laden(); this.meldung.set('Firma gespeichert.');
  }); }
  zugangBearbeiten(name: string, z: Mitgliedschaft): void { this.zugangName = name; this.zugangId = z.mandantenspeicherId; this.zugangRolle = z.rolle; this.zugangAktiv = z.aktiv; }
  async einrichten(m: Speicher): Promise<void> { await this.ausfuehren(async () => {
    await firstValueFrom(mitFehlern(this.http.post('/api/mandantenverwaltung/' + m.mandantenspeicherId + '/einrichten', {})));
    await this.laden(); this.meldung.set('Mandantenspeicher ist bereit.');
  }); }
  async zuordnen(): Promise<void> { await this.ausfuehren(async () => {
    await firstValueFrom(mitFehlern(this.http.put('/api/mandantenverwaltung/' + this.zugangId + '/konten', { name: this.zugangName, rolle: this.zugangRolle, aktiv: this.zugangAktiv })));
    await this.laden(); this.meldung.set('Firmenzugang gespeichert. Betroffene Konten müssen sich erneut anmelden.');
  }); }
  kontoBearbeiten(k: Konto): void { this.kontoName = k.name; this.kontoAktiv = k.aktiv; this.systemverwaltung = k.systemverwaltung; this.passwort = ''; }
  async kontoSpeichern(): Promise<void> { await this.ausfuehren(async () => {
    await firstValueFrom(mitFehlern(this.http.put('/api/mandantenverwaltung/konten/' + encodeURIComponent(this.kontoName), { aktiv: this.kontoAktiv, systemverwaltung: this.systemverwaltung, passwort: this.passwort })));
    this.passwort = ''; this.kontoName = ''; await this.laden(); this.meldung.set('Globales Konto gespeichert.');
  }); }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Anfrage fehlgeschlagen.'); }
    finally { this.arbeitet.set(false); }
  }
}
