import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { SitzungService } from '../sitzung/sitzung.service';
import { mitFehlern } from '../shared/api-fehler';
interface Zugang { mandantenspeicherId: number; name: string; rolle: string; }
@Component({ selector: 'app-mandanten', standalone: true, template: `
<section class="team-panel administration"><h1>Firma auswählen</h1>
<p>Ein Konto, getrennte Datenbestände. Ihre Rolle kann je Firma unterschiedlich sein.</p>
@if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> }
<ul class="management-list">@for (m of mandanten(); track m.mandantenspeicherId) {
  <li><div><strong>{{ m.name }}</strong><p>Mandantenspeicher-ID {{ m.mandantenspeicherId }} · {{ m.rolle }}</p></div>
  <button class="button secondary" [disabled]="arbeitet() || m.mandantenspeicherId === sitzung()?.mandantenspeicherId" (click)="wechseln(m)">
    {{ m.mandantenspeicherId === sitzung()?.mandantenspeicherId ? 'Aktive Firma' : 'Zu ' + m.name + ' wechseln' }}</button></li>
} @empty { <li>Kein aktiver Firmenzugang vorhanden. Die Systemverwaltung kann Ihnen eine Firma zuordnen.</li> }</ul></section>` })
export class MandantenComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly sitzung = inject(SitzungService).sitzung;
  readonly mandanten = signal<Zugang[]>([]);
  readonly fehler = signal(''); readonly arbeitet = signal(false);
  async ngOnInit(): Promise<void> {
    try { this.mandanten.set(await firstValueFrom(mitFehlern(this.http.get<Zugang[]>('/api/mandanten')))); }
    catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Firmen konnten nicht geladen werden.'); }
  }
  async wechseln(m: Zugang): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set('');
    try {
      await firstValueFrom(mitFehlern(this.http.post('/api/mandanten/wechsel', {
        mandantenspeicherId: m.mandantenspeicherId, bisherigeMandantenspeicherId: this.sitzung()?.mandantenspeicherId,
      })));
      // Sämtliche Formulare, Listen und zwischengespeicherten Antworten gehören zur vorherigen Firma.
      location.assign('/dashboard');
    } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Firmenwechsel fehlgeschlagen.'); this.arbeitet.set(false); }
  }
}
