import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { mitFehlern, ApiFehler } from '../shared/api-fehler';
import { heutigesDatum } from '../shared/model';
import { SitzungService } from '../sitzung/sitzung.service';
import { UrlaubskontoComponent } from './urlaubskonto.component';
import { UrlaubZuordnung } from './urlaubsfreigabe.component';
import { ActivatedRoute } from '@angular/router';
import { DownloadService } from '../shared/download.service';
interface Antrag { id: number; mitarbeiterId: number; name: string; beginn: string; ende: string; halberTag: boolean; status: string; notiz: string; entscheidung: string; bearbeiter: string | null; version: number; }
interface Besetzung { warnungen: { datum: string; organisation: string; vorher: number; danach: number; mindestbesetzung: number }[]; hinweise: string[]; }
@Component({ selector: 'app-urlaub', standalone: true, imports: [FormsModule, DatePipe, UrlaubskontoComponent], templateUrl: './urlaub.component.html', styleUrl: './urlaub.component.css' })
export class UrlaubComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly download = inject(DownloadService);
  exportJahr = +heutigesDatum().slice(0, 4);
  async exportieren(): Promise<void> { await this.ausfuehren(() => this.download.laden('/api/urlaub/export.ics', `mein-urlaub-${this.exportJahr}.ics`, { jahr: this.exportJahr })); }
  fokusId: number | null = null;
  readonly sitzung = inject(SitzungService).sitzung;
  readonly profil = signal<UrlaubZuordnung | null>(null);
  readonly antraege = signal<Antrag[]>([]);
  readonly detailAntrag = signal<Antrag | null>(null);
  readonly gesamt = signal(0);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly arbeitet = signal(false);
  readonly heute = heutigesDatum();
  readonly statusTexte: Record<string, string> = { BEANTRAGT: 'Beantragt', GENEHMIGT: 'Genehmigt', ABGELEHNT: 'Abgelehnt', ZURUECKGEZOGEN: 'Zurückgezogen', STORNIERT: 'Storniert' };
  eingabe = { beginn: '', ende: '', halberTag: false, notiz: '' };
  freigaben = false;
  status = 'BEANTRAGT';
  seite = 0;
  entscheidung: { antrag: Antrag; aktion: string; begruendung: string; warnungBestaetigt: boolean; besetzung: Besetzung | null } | null = null;
  ngOnInit(): void {
    const rolle = this.sitzung()?.rolle;
    this.freigaben = rolle === 'PERSONAL' || rolle === 'ADMIN';
    if (this.route.snapshot.queryParamMap.get('eigene') === 'true') this.freigaben = false;
    this.fokusId = Number(this.route.snapshot.queryParamMap.get('antrag')) || null;
    void this.laden();
  }
  async laden(neu = false): Promise<void> {
    if (this.arbeitet()) return;
    if (neu) { this.seite = 0; this.fokusId = null; }
    await this.ausfuehren(async () => { await this.datenLaden(); });
  }
  private async datenLaden(): Promise<void> {
    if (this.fokusId) {
      const [p, d] = await Promise.all([
        firstValueFrom(mitFehlern(this.http.get<{ zuordnung: UrlaubZuordnung | null }>('/api/urlaub/profil'))),
        firstValueFrom(mitFehlern(this.http.get<{ antrag: Antrag; freigabe: boolean }>('/api/urlaub/antraege/' + this.fokusId))),
      ]);
      this.profil.set(p.zuordnung); this.freigaben = d.freigabe; this.status = 'ALLE'; this.antraege.set([d.antrag]); this.gesamt.set(1); return;
    }
    const [p, a] = await Promise.all([
      firstValueFrom(mitFehlern(this.http.get<{ zuordnung: UrlaubZuordnung | null }>('/api/urlaub/profil'))),
      firstValueFrom(mitFehlern(this.http.get<{ eintraege: Antrag[]; gesamt: number }>('/api/urlaub/antraege', { params: { freigaben: this.freigaben, status: this.status, seite: this.seite } }))),
    ]);
    this.profil.set(p.zuordnung); this.antraege.set(a.eintraege); this.gesamt.set(a.gesamt);
    if (!a.eintraege.length && this.seite > 0) { this.seite--; await this.datenLaden(); }
  }
  async beantragen(): Promise<void> {
    await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.post('/api/urlaub/antraege', this.eingabe))); this.fokusId = null; this.eingabe = { beginn: '', ende: '', halberTag: false, notiz: '' }; this.freigaben = false; this.status = 'ALLE'; this.seite = 0; await this.datenLaden(); this.meldung.set('Urlaubsantrag eingereicht.'); });
  }
  detailsAnzeigen(antrag: Antrag, dialog: HTMLDialogElement): void { this.detailAntrag.set(antrag); dialog.showModal(); }
  async vorbereiten(antrag: Antrag, aktion: string): Promise<void> {
    this.entscheidung = null;
    await this.ausfuehren(async () => {
      const besetzung = aktion === 'GENEHMIGEN' ? await firstValueFrom(mitFehlern(this.http.get<Besetzung>(`/api/urlaub/antraege/${antrag.id}/besetzung`))) : null;
      this.entscheidung = { antrag, aktion, begruendung: '', warnungBestaetigt: false, besetzung };
    });
  }
  async entscheiden(): Promise<void> {
    const e = this.entscheidung; if (!e) return;
    await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.post(`/api/urlaub/antraege/${e.antrag.id}/entscheidung`, { aktion: e.aktion, version: e.antrag.version, begruendung: e.begruendung, warnungBestaetigt: e.warnungBestaetigt }))); this.entscheidung = null; await this.datenLaden(); this.meldung.set('Entscheidung gespeichert.'); });
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Anfrage fehlgeschlagen.'); }
    finally { this.arbeitet.set(false); }
  }
}
