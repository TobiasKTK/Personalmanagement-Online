import { Component, OnInit, OnDestroy, ElementRef, ViewChild, inject, input, output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { fehlertext } from '../entwicklung/entwicklung-api.service';
import { Personaldokument } from './personaldaten.model';

@Component({ selector: 'app-personaldokumente', standalone: true, imports: [FormsModule, DecimalPipe], templateUrl: './personaldokumente.component.html', styleUrl: './personaldaten.component.css' })
export class PersonaldokumenteComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly sanitizer = inject(DomSanitizer);
  readonly personId = input.required<number>();
  readonly arbeitetGeaendert = output<boolean>();
  readonly dokumente = signal<Personaldokument[]>([]);
  readonly gesamt = signal(0); readonly arbeitet = signal(false); readonly geladen = signal(false);
  readonly fehler = signal(''); readonly meldung = signal('');
  readonly vorschau = signal<SafeResourceUrl | null>(null);
  readonly ausgewaehlt = signal<Personaldokument | null>(null);
  readonly arten = [{ id: 'ABRECHNUNG', name: 'Lohn- / Gehaltsabrechnung' }, { id: 'LOHNSTEUER', name: 'Lohnsteuerbescheinigung' }, { id: 'SOZIALVERSICHERUNG', name: 'Sozialversicherungsmeldung' }, { id: 'VERTRAG', name: 'Vertrag' }, { id: 'SONSTIGES', name: 'Sonstiges' }];
  eingabe = { titel: '', art: 'ABRECHNUNG', quelle: 'AGENDA', monat: '' };
  datei: File | null = null; seite = 0; downloadUrl = '';
  private beendet = false;
  @ViewChild('dateifeld') private dateifeld?: ElementRef<HTMLInputElement>;
  geaendert(): boolean { return !!this.datei || !!this.eingabe.titel || !!this.eingabe.monat || this.eingabe.art !== 'ABRECHNUNG' || this.eingabe.quelle !== 'AGENDA'; }
  entwurfVerwerfen(): void {
    this.eingabe = { titel: '', art: 'ABRECHNUNG', quelle: 'AGENDA', monat: '' }; this.datei = null;
    if (this.dateifeld) this.dateifeld.nativeElement.value = '';
  }
  ngOnInit(): void { void this.laden(); }
  ngOnDestroy(): void { this.beendet = true; this.vorschauSchliessen(); }
  art(id: string): string { return this.arten.find(a => a.id === id)?.name ?? id; }
  dateiWaehlen(e: Event): void { const input = e.target as HTMLInputElement; this.datei = input.files?.[0] ?? null; }
  async laden(): Promise<void> { await this.ausfuehren(() => this.datenLaden()); }
  private async datenLaden(): Promise<void> {
    const r = await firstValueFrom(mitFehlern(this.http.get<{ eintraege: Personaldokument[]; gesamt: number }>(this.pfad(), { params: { seite: this.seite } })));
    this.dokumente.set(r.eintraege); this.gesamt.set(r.gesamt); this.geladen.set(true);
    if (!r.eintraege.length && this.seite > 0) { this.seite--; await this.datenLaden(); }
  }
  async importieren(): Promise<void> {
    const datei = this.datei;
    if (!datei || datei.size === 0 || datei.size > 5 * 1024 * 1024) { this.fehler.set('Bitte eine PDF-Datei mit höchstens 5 MiB auswählen.'); return; }
    await this.ausfuehren(async () => {
      const form = new FormData();
      for (const [name, wert] of Object.entries(this.eingabe)) form.append(name, wert);
      form.append('datei', datei);
      await firstValueFrom(mitFehlern(this.http.post(this.pfad(), form)));
      this.entwurfVerwerfen(); this.seite = 0;
      await this.datenLaden(); this.meldung.set('PDF-Dokument importiert.');
    });
  }
  async ansehen(d: Personaldokument): Promise<void> {
    await this.ausfuehren(async () => {
      const inhalt = await firstValueFrom(mitFehlern(this.http.get(this.pfad() + '/' + d.id + '/inhalt', { responseType: 'blob' })));
      if (this.beendet) return;
      this.vorschauSchliessen();
      // Ausschließlich selbst erstellte Blob-URLs aus dem geschützten PDF-Endpunkt; keine eingegebenen URLs.
      this.downloadUrl = URL.createObjectURL(new Blob([inhalt], { type: 'application/pdf' }));
      this.vorschau.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.downloadUrl)); this.ausgewaehlt.set(d);
    });
  }
  vorschauSchliessen(): void { if (this.downloadUrl) URL.revokeObjectURL(this.downloadUrl); this.downloadUrl = ''; this.vorschau.set(null); this.ausgewaehlt.set(null); }
  async loeschen(d: Personaldokument): Promise<void> {
    if (!confirm('Das PDF-Dokument „' + d.titel + '“ endgültig löschen?')) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.delete(this.pfad() + '/' + d.id)));
      if (this.ausgewaehlt()?.id === d.id) this.vorschauSchliessen();
      await this.datenLaden(); this.meldung.set('Dokument gelöscht.');
    });
  }
  private pfad(): string { return `/api/mitarbeiter/${this.personId()}/personaldaten/dokumente`; }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.arbeitetGeaendert.emit(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(fehlertext(e)); }
    finally { this.arbeitet.set(false); if (!this.beendet) this.arbeitetGeaendert.emit(false); }
  }
}
