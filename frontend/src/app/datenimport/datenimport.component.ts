import { Component, HostListener, LOCALE_ID, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe, DatePipe, formatNumber, registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { fehlertext } from '../entwicklung/entwicklung-api.service';
import { ImportDaten, ImportVorschau, importFelder } from './datenimport.model';

registerLocaleData(localeDe);

@Component({ selector: 'app-datenimport', standalone: true, imports: [FormsModule, DecimalPipe, DatePipe, RouterLink],
  providers: [{ provide: LOCALE_ID, useValue: 'de-DE' }],
  templateUrl: './datenimport.component.html', styleUrl: './datenimport.component.css' })
export class DatenimportComponent {
  private readonly http = inject(HttpClient);
  readonly felder = importFelder;
  readonly arbeitet = signal(false); readonly fehler = signal(''); readonly meldung = signal('');
  readonly spalten = signal<string[]>([]); readonly beispiele = signal<string[][]>([]);
  readonly vorschau = signal<ImportVorschau | null>(null); readonly importiert = signal<number | null>(null);
  datei: File | null = null; trennzeichen = ';'; zuordnung: Record<string, number | null> = {};
  seite = 0; private csv = '';

  @HostListener('window:beforeunload', ['$event']) vorSchliessen(e: BeforeUnloadEvent): void {
    if (this.offen()) { e.preventDefault(); e.returnValue = ''; }
  }
  verlassenErlauben(): boolean {
    if (this.arbeitet()) {
      this.meldung.set('Der Import wird noch bearbeitet. Bitte warten Sie auf das Ergebnis, bevor Sie die Seite verlassen.');
      return false;
    }
    return !this.offen() || confirm('Die noch nicht übernommene Importvorschau verwerfen?');
  }
  private offen(): boolean { return this.arbeitet() || (!!this.csv && this.importiert() === null); }
  dateiWaehlen(e: Event): void {
    this.datei = (e.target as HTMLInputElement).files?.[0] ?? null; this.zuruecksetzen();
  }
  zuruecksetzen(): void {
    this.csv = ''; this.spalten.set([]); this.beispiele.set([]); this.zuordnung = {};
    this.vorschau.set(null); this.importiert.set(null); this.fehler.set(''); this.meldung.set(''); this.seite = 0;
  }
  zuordnungGeaendert(): void { this.vorschau.set(null); this.importiert.set(null); this.meldung.set(''); this.seite = 0; }
  private payload(): { csv: string; trennzeichen: string; zuordnung: Record<string, number> } {
    const zuordnung: Record<string, number> = {};
    for (const [feld, index] of Object.entries(this.zuordnung)) if (index !== null) zuordnung[feld] = index;
    return { csv: this.csv, trennzeichen: this.trennzeichen, zuordnung };
  }
  async lesen(): Promise<void> {
    const datei = this.datei;
    if (!datei || !datei.size || datei.size > 1024 * 1024) { this.fehler.set('Bitte eine UTF-8-CSV mit höchstens 1 MiB auswählen.'); return; }
    await this.ausfuehren(async () => {
      this.spalten.set([]); this.vorschau.set(null); this.importiert.set(null);
      this.zuordnung = Object.fromEntries(this.felder.map(feld => [feld.id, null]));
      try { this.csv = new TextDecoder('utf-8', { fatal: true }).decode(await datei.arrayBuffer()); }
      catch { throw new Error('Die Datei ist nicht als UTF-8 gespeichert. Bitte als CSV UTF-8 exportieren.'); }
      const r = await firstValueFrom(mitFehlern(this.http.post<{ spalten: string[]; zeilen: string[][] }>('/api/importe/csv/spalten', this.payload())));
      this.spalten.set(r.spalten); this.beispiele.set(r.zeilen);
      const alias: Record<string, string> = { nummer: 'personalnummer', eintritt: 'eintrittsdatum', austritt: 'austrittsdatum', stunden: 'wochenstunden', abteilung: 'organisationseinheit', kurzel: 'kuerzel' };
      r.spalten.forEach((name, index) => {
        const normal = name.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '').replace(/[^a-z]/g, '');
        const feld = alias[normal] ?? normal;
        if (this.felder.some(f => f.id === feld) && this.zuordnung[feld] == null) this.zuordnung[feld] = index;
      });
      this.meldung.set('Spalten eingelesen. Bitte die Zuordnung prüfen.');
    });
  }
  async pruefen(): Promise<void> {
    await this.ausfuehren(async () => {
      this.vorschau.set(null); this.importiert.set(null); this.seite = 0;
      const r = await firstValueFrom(mitFehlern(this.http.post<ImportVorschau>('/api/importe/mitarbeiter/vorschau', this.payload())));
      this.vorschau.set(r);
      this.meldung.set(r.fehleranzahl ? 'Die CSV enthält Fehler. Bitte die Datei korrigieren und erneut einlesen.' : 'Vorschau geprüft. Es wurden noch keine Mitarbeitenden angelegt.');
    });
  }
  async uebernehmen(): Promise<void> {
    const id = this.vorschau()?.id;
    if (!id || this.importiert() !== null) return;
    await this.ausfuehren(async () => {
      const r = await firstValueFrom(mitFehlern(this.http.post<{ importiert: number }>(`/api/importe/mitarbeiter/${id}/uebernahme`, {})));
      this.importiert.set(r.importiert); this.meldung.set(`${r.importiert} Mitarbeitende importiert.`);
    });
  }
  sichtbareZeilen() { return this.vorschau()?.zeilen.slice(this.seite * 25, (this.seite + 1) * 25) ?? []; }
  wert(daten: ImportDaten, feld: keyof ImportDaten): string {
    const wert = daten[feld];
    return feld === 'wochenstunden' && typeof wert === 'number'
      ? formatNumber(wert, 'de-DE', '1.0-2') : String(wert ?? '—');
  }
  vorlage(): void {
    const csv = '\uFEFFpersonalnummer;vorname;nachname;eintrittsdatum;wochenstunden;organisationseinheit\r\n1001;Erika;Muster;2026-01-01;32;\r\n';
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = 'mitarbeiter-import-vorlage.csv'; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.meldung.set(''); this.fehler.set(fehlertext(e)); } finally { this.arbeitet.set(false); }
  }
}
