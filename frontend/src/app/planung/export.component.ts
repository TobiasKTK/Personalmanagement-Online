import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { DownloadService } from '../shared/download.service';
import { heutigesDatum } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';
@Component({ selector: 'app-export', standalone: true, imports: [FormsModule], template: `
<section class="team-panel administration"><h1>Exporte</h1><p>Exportiert alle Treffer der Auswahl, unabhängig von der Seitengröße. Der Stichtag bestimmt die Organisation und den Beschäftigungsstatus.</p>
@if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> } @if (meldung()) { <p role="status">{{ meldung() }}</p> }
<form (ngSubmit)="exportieren()"><fieldset [disabled]="arbeitet()"><legend>Auswahl exportieren</legend><div class="filter-grid">
<label class="field">Mitarbeitersuche<input name="suche" [(ngModel)]="suche" maxlength="120"></label>
<label class="field">Organisationseinheit<select name="organisation" [(ngModel)]="organisation"><option [ngValue]="null">Alle Organisationseinheiten</option>@for (o of organisationen(); track o.id) { <option [ngValue]="o.id">{{ o.name }}</option> }</select></label>
<label class="field">Beschäftigung<select name="beschaeftigung" [(ngModel)]="beschaeftigung"><option value="ALLE">Alle</option><option value="AKTIV">Aktiv</option><option value="KUENFTIG">Künftig</option><option value="AUSGESCHIEDEN">Ausgeschieden</option></select></label>
<label class="field">Stichtag<input type="date" name="stichtag" [(ngModel)]="stichtag" required></label>
<label class="field">Exportformat<select name="format" [(ngModel)]="format"><option value="mitarbeiter.csv">Mitarbeiterliste (CSV)</option><option value="urlaubskonten.csv">Urlaubskonten (CSV)</option><option value="urlaub.ics">Genehmigte Urlaube (ICS)</option></select></label>
@if (format !== 'mitarbeiter.csv') { <label class="field">Urlaubsjahr<input type="number" name="jahr" [(ngModel)]="jahr" min="1" max="9999" required></label> }
<button class="button primary">Datei herunterladen</button>
</div></fieldset></form>
<p>CSV: UTF-8, Semikolon als Trennzeichen und Dezimalkomma. Nicht eingerichtete Urlaubskonten bleiben leer.</p>
<p>ICS: genehmigte Anträge, die das gewählte Jahr berühren, jeweils mit vollständigem Zeitraum. Halbe Tage erscheinen als gekennzeichnete Ganztagstermine. Ein Import in Outlook ist eine Momentaufnahme; spätere Stornierungen werden dort nicht automatisch entfernt.</p></section>` })
export class ExportComponent implements OnInit {
  private readonly http = inject(HttpClient); private readonly download = inject(DownloadService);
  readonly organisationen = signal<{id: number; name: string}[]>([]); readonly arbeitet = signal(false); readonly fehler = signal(''); readonly meldung = signal('');
  suche = ''; organisation: number | null = null; beschaeftigung = 'ALLE'; stichtag = heutigesDatum(); jahr = +this.stichtag.slice(0, 4); format = 'mitarbeiter.csv';
  async ngOnInit(): Promise<void> { try { this.organisationen.set(await firstValueFrom(mitFehlern(this.http.get<{id: number; name: string}[]>('/api/organisationseinheiten')))); } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Organisationen konnten nicht geladen werden.'); } }
  async exportieren(): Promise<void> { if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set(''); try {
    const params: Record<string, string | number> = { suche: this.suche, beschaeftigung: this.beschaeftigung, stichtag: this.stichtag, jahr: this.jahr };
    if (this.organisation !== null) params['organisationseinheitId'] = this.organisation;
    await this.download.laden('/api/export/' + this.format, this.format, params); this.meldung.set('Export zum Herunterladen bereitgestellt.');
  } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Export fehlgeschlagen.'); } finally { this.arbeitet.set(false); } }
}
