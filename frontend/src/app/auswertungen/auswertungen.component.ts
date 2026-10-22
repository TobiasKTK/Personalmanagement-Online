import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { heutigesDatum, Organisation } from '../shared/model';
import { DownloadService } from '../shared/download.service';
import { SitzungService } from '../sitzung/sitzung.service';
import { Auswertung, Seite } from './auswertungen.model';

type Bereich = 'organisationsSeite' | 'stellenSeite' | 'bedarfsSeite';
@Component({ selector: 'app-auswertungen', standalone: true, imports: [FormsModule, RouterLink],
  templateUrl: './auswertungen.component.html', styleUrl: './auswertungen.component.css' })
export class AuswertungenComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly download = inject(DownloadService);
  readonly sitzung = inject(SitzungService);
  readonly ergebnis = signal<Auswertung | null>(null);
  readonly organisationen = signal<Organisation[]>([]);
  readonly arbeitet = signal(false);
  readonly exportiert = signal(false);
  readonly fehler = signal('');
  readonly meldung = signal('');
  private organisationenGeladen = false;
  organisation: number | null = null;
  stichtag = heutigesDatum();
  private readonly zahlen = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 4 });

  ngOnInit(): void { void this.aktualisieren(); }
  zahl(wert: number | null): string { return wert === null ? 'Nicht bekannt' : this.zahlen.format(wert); }
  datum(wert: string): string { return wert.split('-').reverse().join('.'); }
  filterGeaendert(): boolean {
    const r = this.ergebnis();
    return !r || r.stichtag !== this.stichtag || r.organisationseinheitId !== this.organisation;
  }
  seitenzahl(s: Seite<unknown>): number { return Math.max(1, Math.ceil(s.gesamt / s.groesse)); }
  async aktualisieren(): Promise<void> { await this.laden({ organisationsSeite: 0, stellenSeite: 0, bedarfsSeite: 0 }); }
  async blaettern(bereich: Bereich, seite: number): Promise<void> {
    const r = this.ergebnis();
    if (!r || this.filterGeaendert()) return;
    await this.laden({ organisationsSeite: r.organisationen.seite, stellenSeite: r.offeneStellen.seite, bedarfsSeite: r.qualifikationsluecken.seite, [bereich]: seite });
  }
  private async laden(seiten: Record<Bereich, number>): Promise<void> {
    if (this.arbeitet() || this.exportiert()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    const params: Record<string, string | number> = { stichtag: this.stichtag, ...seiten };
    if (this.organisation !== null) params['organisationseinheitId'] = this.organisation;
    try {
      if (!this.organisationenGeladen) {
        this.organisationen.set(await firstValueFrom(mitFehlern(this.http.get<Organisation[]>('/api/organisationseinheiten'))));
        this.organisationenGeladen = true;
      }
      this.ergebnis.set(await firstValueFrom(mitFehlern(this.http.get<Auswertung>('/api/auswertungen', { params }))));
      this.meldung.set('Auswertung aktualisiert.');
    } catch (e) {
      this.ergebnis.set(null);
      this.fehler.set(e instanceof Error ? e.message : 'Die Auswertung konnte nicht geladen werden. Bitte erneut versuchen.');
    } finally { this.arbeitet.set(false); }
  }
  async exportieren(): Promise<void> {
    const r = this.ergebnis();
    if (!r || this.filterGeaendert() || this.arbeitet() || this.exportiert()) return;
    this.exportiert.set(true); this.fehler.set(''); this.meldung.set('');
    const params: Record<string, string | number> = { stichtag: r.stichtag };
    if (r.organisationseinheitId !== null) params['organisationseinheitId'] = r.organisationseinheitId;
    try {
      await this.download.laden('/api/auswertungen/export.csv', `hr-auswertung-${r.stichtag}.csv`, params);
      this.meldung.set('Vollständige Auswertung zum Herunterladen bereitgestellt.');
    } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Der Export ist fehlgeschlagen. Bitte erneut versuchen.'); }
    finally { this.exportiert.set(false); }
  }
}
