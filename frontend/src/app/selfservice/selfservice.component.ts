import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { fehlertext } from '../entwicklung/entwicklung-api.service';
import { SitzungService } from '../sitzung/sitzung.service';
import { Antrag, Dokument, Profil, Seite } from './selfservice.model';

@Component({ selector: 'app-selfservice', standalone: true, imports: [FormsModule, DatePipe, RouterLink], templateUrl: './selfservice.component.html', styleUrl: './selfservice.component.css' })
export class SelfServiceComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly sanitizer = inject(DomSanitizer);
  readonly sitzung = inject(SitzungService);
  readonly pruefung = this.route.snapshot.data['pruefung'] === true;
  readonly profil = signal<Profil | null>(null);
  readonly antraege = signal<Antrag[]>([]); readonly gesamt = signal(0);
  readonly dokumente = signal<Dokument[]>([]); readonly dokumentGesamt = signal(0);
  readonly arbeitet = signal(false); readonly geladen = signal(false);
  readonly fehler = signal(''); readonly meldung = signal('');
  readonly vorschau = signal<SafeResourceUrl | null>(null); readonly dokument = signal<Dokument | null>(null);
  kontakt = { telefon: '', email: '' }; bank = { kontoinhaber: '', iban: '', bic: '', institut: '' };
  gruende: Record<number, string> = {}; seite = 0; dokumentSeite = 0; downloadUrl = '';
  private beendet = false;
  ngOnInit(): void { void this.laden(); }
  ngOnDestroy(): void { this.beendet = true; this.schliessen(); }
  personal(): boolean { return ['PERSONAL', 'ADMIN'].includes(this.sitzung.sitzung()?.rolle ?? ''); }
  status(a: Antrag): string { return { BEANTRAGT: 'Beantragt', GENEHMIGT: 'Genehmigt', ABGELEHNT: 'Abgelehnt' }[a.status]; }
  werte(daten: Antrag['nachher']): { name: string; wert: string }[] {
    const namen: Record<string, string> = { telefon: 'Telefon', email: 'E-Mail', kontoinhaber: 'Kontoinhaber', iban: 'IBAN', bic: 'BIC', institut: 'Institut' };
    return Object.entries(daten).map(([k, wert]) => ({ name: namen[k] ?? k, wert }));
  }
  offen(art: string): boolean { return this.antraege().some(a => a.art === art && a.status === 'BEANTRAGT'); }
  async laden(): Promise<void> { await this.ausfuehren(async () => { await this.datenLaden(); this.geladen.set(true); }); }
  private async datenLaden(): Promise<void> {
    this.schliessen();
    // Erst nach erfolgreichen Kernabfragen wieder anzeigen: Eine entzogene Zuordnung darf keine alten Daten stehen lassen.
    this.profil.set(null); this.antraege.set([]); this.gesamt.set(0);
    this.dokumente.set([]); this.dokumentGesamt.set(0);
    this.kontakt = { telefon: '', email: '' }; this.bank = { kontoinhaber: '', iban: '', bic: '', institut: '' };
    const liste = this.pruefung ? '/pruefung' : '/antraege';
    const a = await firstValueFrom(mitFehlern(this.http.get<Seite<Antrag>>('/api/self-service' + liste, { params: { seite: this.seite } })));
    if (!this.pruefung) {
      const p = await firstValueFrom(mitFehlern(this.http.get<Profil>('/api/self-service/profil')));
      const d = await firstValueFrom(mitFehlern(this.http.get<Seite<Dokument>>('/api/self-service/dokumente', { params: { seite: this.dokumentSeite } })));
      if (this.beendet) return;
      this.profil.set(p); this.kontakt = { ...p.kontakt }; this.bank = { ...p.bank };
      this.dokumente.set(d.eintraege); this.dokumentGesamt.set(d.gesamt);
    }
    if (this.beendet) return;
    this.antraege.set(a.eintraege); this.gesamt.set(a.gesamt);
    const id = Number(this.route.snapshot.queryParamMap.get('antrag'));
    if (id && !a.eintraege.some(e => e.id === id)) {
      try {
        const detail = await firstValueFrom(mitFehlern(this.http.get<Antrag>('/api/self-service/antraege/' + id)));
        if (this.beendet) return;
        if (this.pruefung || (detail.mitarbeiterId === this.profil()?.mitarbeiterId && detail.antragsteller === this.sitzung.sitzung()?.name)) {
          this.antraege.update(e => [detail, ...e]);
        } else this.fehler.set('Der verlinkte Antrag gehört nicht zu Ihrem aktuellen Mitarbeiterprofil.');
      } catch (e) {
        // Ein veralteter Direktlink darf das aktuelle Profil nicht blockieren.
        this.fehler.set(fehlertext(e));
      }
    }
    if (id) requestAnimationFrame(() => document.getElementById('antrag-' + id)?.scrollIntoView({ block: 'start' }));
  }
  async einreichen(art: 'KONTAKT' | 'BANK'): Promise<void> {
    const p = this.profil(); if (!p) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.post('/api/self-service/antraege', art === 'KONTAKT'
        ? { mitarbeiterId: p.mitarbeiterId, art, kontakt: this.kontakt, bestandsversion: p.kontaktVersion }
        : { mitarbeiterId: p.mitarbeiterId, art, bank: this.bank, bestandsversion: p.bankVersion })));
      this.seite = 0; await this.datenLaden(); this.meldung.set('Änderung zur Prüfung eingereicht. Bis zur Genehmigung gelten die bisherigen Daten.');
    });
  }
  async entscheiden(a: Antrag, status: 'GENEHMIGT' | 'ABGELEHNT'): Promise<void> {
    const grund = this.gruende[a.id] ?? '';
    if (status === 'ABGELEHNT' && !grund.trim()) { this.fehler.set('Bitte die Ablehnung begründen.'); return; }
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.put(`/api/self-service/antraege/${a.id}/entscheidung`, { status, grund, version: a.version })));
      await this.datenLaden(); this.meldung.set(status === 'GENEHMIGT' ? 'Änderung genehmigt und in den Personalbestand übernommen.' : 'Antrag mit Begründung abgelehnt.');
    });
  }
  async ansehen(d: Dokument): Promise<void> {
    await this.ausfuehren(async () => {
      this.schliessen();
      const blob = await firstValueFrom(mitFehlern(this.http.get(`/api/self-service/dokumente/${d.id}/inhalt`, { responseType: 'blob' })));
      if (this.beendet) return;
      this.downloadUrl = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
      // Nur vom geschützten PDF-Endpunkt empfangene Inhalte erhalten eine lokale Blob-URL.
      this.vorschau.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.downloadUrl + '#view=FitH')); this.dokument.set(d);
    });
  }
  async herunterladen(): Promise<void> {
    const d = this.dokument(); if (!d) return;
    await this.ausfuehren(async () => {
      // Jeder neue Abruf prüft Eigentümerschaft und die aktuelle Freigabe erneut.
      let blob: Blob;
      try { blob = await firstValueFrom(mitFehlern(this.http.get(`/api/self-service/dokumente/${d.id}/inhalt`, { responseType: 'blob' }))); }
      catch (e) { this.schliessen(); throw e; }
      if (this.beendet) return;
      const url = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
      const link = document.createElement('a'); link.href = url; link.download = `abrechnung-${d.id}.pdf`; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }
  schliessen(): void { if (this.downloadUrl) URL.revokeObjectURL(this.downloadUrl); this.downloadUrl = ''; this.vorschau.set(null); this.dokument.set(null); }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(fehlertext(e)); } finally { this.arbeitet.set(false); }
  }
}
