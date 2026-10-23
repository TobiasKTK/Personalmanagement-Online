import { ChangeDetectorRef, Component, DestroyRef, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
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
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);
  @ViewChild('entscheidungsdialog') private entscheidungsdialog!: ElementRef<HTMLDialogElement>;
  @ViewChild('antragslisteTitel') private antragslisteTitel!: ElementRef<HTMLElement>;
  private entscheidungsAusloeser: HTMLElement | null = null;
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
  readonly entscheidungsTitel: Record<string, string> = { GENEHMIGEN: 'Urlaub genehmigen', ABLEHNEN: 'Urlaubsantrag ablehnen', ZURUECKZIEHEN: 'Urlaubsantrag zurückziehen', STORNIEREN: 'Urlaub stornieren' };
  eingabe = { beginn: '', ende: '', halberTag: false, notiz: '' };
  freigaben = false;
  status = 'BEANTRAGT';
  filter = { freigaben: false, status: 'BEANTRAGT' };
  seite = 0;
  entscheidung: { antrag: Antrag; aktion: string; begruendung: string; warnungBestaetigt: boolean; besetzung: Besetzung | null } | null = null;
  ngOnInit(): void {
    const rolle = this.sitzung()?.rolle;
    this.freigaben = rolle === 'PERSONAL' || rolle === 'ADMIN' || rolle === 'TEAMLEITUNG';
    if (this.route.snapshot.queryParamMap.get('eigene') === 'true') this.freigaben = false;
    this.filter = { freigaben: this.freigaben, status: this.status };
    this.fokusId = Number(this.route.snapshot.queryParamMap.get('antrag')) || null;
    void this.laden();
  }
  async laden(neu = false): Promise<void> {
    if (this.arbeitet()) return;
    await this.ausfuehren(() => this.datenLaden(neu ? { ...this.filter, seite: 0 } : undefined));
  }
  private async datenLaden(auswahl?: { freigaben: boolean; status: string; seite: number }): Promise<void> {
    if (this.fokusId && !auswahl) {
      const [p, d] = await Promise.all([
        firstValueFrom(mitFehlern(this.http.get<{ zuordnung: UrlaubZuordnung | null }>('/api/urlaub/profil'))),
        firstValueFrom(mitFehlern(this.http.get<{ antrag: Antrag; freigabe: boolean }>('/api/urlaub/antraege/' + this.fokusId))),
      ]);
      this.profil.set(p.zuordnung); this.freigaben = d.freigabe; this.status = 'ALLE'; this.filter = { freigaben: this.freigaben, status: this.status }; this.antraege.set([d.antrag]); this.gesamt.set(1); return;
    }
    const params = auswahl ?? { freigaben: this.freigaben, status: this.status, seite: this.seite };
    const [p, a] = await Promise.all([
      firstValueFrom(mitFehlern(this.http.get<{ zuordnung: UrlaubZuordnung | null }>('/api/urlaub/profil'))),
      firstValueFrom(mitFehlern(this.http.get<{ eintraege: Antrag[]; gesamt: number }>('/api/urlaub/antraege', { params }))),
    ]);
    if (!a.eintraege.length && params.seite > 0) { await this.datenLaden({ ...params, seite: params.seite - 1 }); return; }
    this.profil.set(p.zuordnung); this.antraege.set(a.eintraege); this.gesamt.set(a.gesamt);
    this.freigaben = params.freigaben; this.status = params.status; this.seite = params.seite; this.fokusId = null;
  }
  async beantragen(): Promise<void> {
    await this.ausfuehren(async () => { await firstValueFrom(mitFehlern(this.http.post('/api/urlaub/antraege', this.eingabe))); this.eingabe = { beginn: '', ende: '', halberTag: false, notiz: '' }; this.filter = { freigaben: false, status: 'ALLE' }; await this.datenLaden({ ...this.filter, seite: 0 }); this.meldung.set('Urlaubsantrag eingereicht.'); });
  }
  detailsAnzeigen(antrag: Antrag, dialog: HTMLDialogElement): void { this.detailAntrag.set(antrag); dialog.showModal(); }
  async vorbereiten(antrag: Antrag, aktion: string): Promise<void> {
    if (this.arbeitet()) return;
    this.entscheidungsAusloeser = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.entscheidung = null;
    await this.ausfuehren(async () => {
      const besetzung = aktion === 'GENEHMIGEN' ? await firstValueFrom(mitFehlern(this.http.get<Besetzung>(`/api/urlaub/antraege/${antrag.id}/besetzung`))) : null;
      this.entscheidung = { antrag, aktion, begruendung: '', warnungBestaetigt: false, besetzung };
    });
    if (this.entscheidung && !this.destroyRef.destroyed) {
      this.changeDetector.detectChanges();
      this.entscheidungsdialog.nativeElement.showModal();
    }
  }
  entscheidungAbbrechen(event?: Event): void {
    event?.preventDefault();
    if (this.arbeitet()) return;
    this.entscheidungsdialog.nativeElement.close();
    this.entscheidung = null;
    this.fehler.set('');
    if (this.entscheidungsAusloeser?.isConnected) this.entscheidungsAusloeser.focus();
    else this.antragslisteTitel.nativeElement.focus();
  }
  async besetzungPruefen(): Promise<void> {
    const e = this.entscheidung; if (!e) return;
    await this.ausfuehren(async () => {
      e.warnungBestaetigt = false;
      e.besetzung = null;
      e.besetzung = await firstValueFrom(mitFehlern(this.http.get<Besetzung>(`/api/urlaub/antraege/${e.antrag.id}/besetzung`)));
    });
  }
  async entscheiden(): Promise<void> {
    const e = this.entscheidung; if (!e) return;
    if (e.aktion === 'GENEHMIGEN' && (!e.besetzung || (e.besetzung.warnungen.length && !e.warnungBestaetigt))) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.post(`/api/urlaub/antraege/${e.antrag.id}/entscheidung`, { aktion: e.aktion, version: e.antrag.version, begruendung: e.begruendung, warnungBestaetigt: e.warnungBestaetigt })));
      this.entscheidungsdialog.nativeElement.close(); this.entscheidung = null;
      this.antragslisteTitel.nativeElement.focus();
      this.meldung.set('Entscheidung gespeichert.');
      await this.datenLaden();
    });
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Anfrage fehlgeschlagen.'); }
    finally { this.arbeitet.set(false); }
  }
}
