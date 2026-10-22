import { Component, OnInit, OnDestroy, ElementRef, ViewChild, inject, input, output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { fehlertext } from '../entwicklung/entwicklung-api.service';
import { Dokumentbedarf, Personaldokument } from './personaldaten.model';

@Component({ selector: 'app-personaldokumente', standalone: true, imports: [FormsModule, DatePipe, DecimalPipe], templateUrl: './personaldokumente.component.html', styleUrl: './personaldaten.component.css' })
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
  readonly versionZiel = signal<Personaldokument | null>(null);
  readonly historieZiel = signal<Personaldokument | null>(null);
  readonly versionen = signal<Personaldokument[]>([]);
  readonly bedarf = signal<Dokumentbedarf | null>(null);
  readonly arten = [{ id: 'ABRECHNUNG', name: 'Lohn- / Gehaltsabrechnung' }, { id: 'LOHNSTEUER', name: 'Lohnsteuerbescheinigung' }, { id: 'SOZIALVERSICHERUNG', name: 'Sozialversicherungsmeldung' }, { id: 'VERTRAG', name: 'Vertrag' }, { id: 'ZEUGNIS', name: 'Zeugnis' }, { id: 'ZERTIFIKAT', name: 'Zertifikat' }, { id: 'SONSTIGES', name: 'Sonstiges' }];
  eingabe = { titel: '', art: 'ABRECHNUNG', quelle: 'AGENDA', monat: '' };
  datei: File | null = null; seite = 0; downloadUrl = '';
  bedarfArten: string[] = [];
  private bedarfOriginal: string[] = [];
  private bedarfVersion = 0;
  private beendet = false;
  @ViewChild('dateifeld') private dateifeld?: ElementRef<HTMLInputElement>;
  @ViewChild('titelfeld') private titelfeld?: ElementRef<HTMLInputElement>;
  @ViewChild('historieUeberschrift') private historieUeberschrift?: ElementRef<HTMLElement>;
  geaendert(): boolean { return this.uploadGeaendert() || this.bedarfGeaendert(); }
  private uploadGeaendert(): boolean { return !!this.datei || !!this.eingabe.titel || !!this.eingabe.monat || this.eingabe.art !== 'ABRECHNUNG' || this.eingabe.quelle !== 'AGENDA'; }
  bedarfGeaendert(): boolean { return this.bedarfArten.length !== this.bedarfOriginal.length || this.bedarfArten.some(a => !this.bedarfOriginal.includes(a)); }
  entwurfVerwerfen(): void { this.uploadVerwerfen(); this.bedarfArten = [...this.bedarfOriginal]; }
  uploadVerwerfen(): void {
    this.eingabe = { titel: '', art: 'ABRECHNUNG', quelle: 'AGENDA', monat: '' }; this.datei = null; this.versionZiel.set(null);
    if (this.dateifeld) this.dateifeld.nativeElement.value = '';
  }
  ngOnInit(): void { void this.laden(); }
  ngOnDestroy(): void { this.beendet = true; this.vorschauSchliessen(); }
  art(id: string): string { return this.arten.find(a => a.id === id)?.name ?? id; }
  dateiWaehlen(e: Event): void { const input = e.target as HTMLInputElement; this.datei = input.files?.[0] ?? null; }
  async laden(): Promise<void> { await this.ausfuehren(() => this.datenLaden()); }
  private async datenLaden(): Promise<void> {
    const [r, bedarf] = await Promise.all([
      firstValueFrom(mitFehlern(this.http.get<{ eintraege: Personaldokument[]; gesamt: number }>(this.pfad(), { params: { seite: this.seite } }))),
      this.get<Dokumentbedarf>('/bedarf'),
    ]);
    if (!this.bedarfGeaendert()) this.bedarfUebernehmen(bedarf); else this.bedarf.set(bedarf);
    this.dokumente.set(r.eintraege); this.gesamt.set(r.gesamt); this.geladen.set(true);
    if (!r.eintraege.length && this.seite > 0) { this.seite--; await this.datenLaden(); return; }
    const historie = this.historieZiel();
    if (historie) this.versionen.set(await this.get<Personaldokument[]>('/' + historie.id + '/versionen'));
  }
  private get<T>(ende: string): Promise<T> { return firstValueFrom(mitFehlern(this.http.get<T>(this.pfad() + ende))); }
  bedarfWaehlen(art: string, e: Event): void {
    this.bedarfArten = (e.target as HTMLInputElement).checked ? [...this.bedarfArten, art] : this.bedarfArten.filter(a => a !== art);
  }
  private bedarfUebernehmen(b: Dokumentbedarf): void {
    this.bedarf.set(b); this.bedarfArten = [...b.benoetigt]; this.bedarfOriginal = [...b.benoetigt]; this.bedarfVersion = b.version;
  }
  async bedarfSpeichern(): Promise<void> {
    await this.ausfuehren(async () => {
      this.bedarfUebernehmen(await firstValueFrom(mitFehlern(this.http.put<Dokumentbedarf>(this.pfad() + '/bedarf', { version: this.bedarfVersion, arten: this.bedarfArten }))));
      this.meldung.set('Dokumentbedarf gespeichert.');
    });
  }
  async bedarfNeuLaden(): Promise<void> {
    if (this.bedarfGeaendert() && !confirm('Ungespeicherten Dokumentbedarf verwerfen und neu laden?')) return;
    await this.ausfuehren(async () => this.bedarfUebernehmen(await this.get<Dokumentbedarf>('/bedarf')));
  }
  versionVorbereiten(d: Personaldokument): void {
    if (this.uploadGeaendert() && !confirm('Ungespeicherten Dokumentimport verwerfen und eine neue Version vorbereiten?')) return;
    this.uploadVerwerfen(); this.versionZiel.set(d); this.fehler.set(''); this.meldung.set('');
    this.eingabe = { titel: d.titel, art: d.art, quelle: d.quelle, monat: d.monat };
    this.titelfeld?.nativeElement.focus();
  }
  async versionenAnzeigen(d: Personaldokument): Promise<void> {
    await this.ausfuehren(async () => {
      const versionen = await this.get<Personaldokument[]>('/' + d.id + '/versionen');
      this.historieZiel.set(d); this.versionen.set(versionen);
    });
    // Die neue Überschrift wird erst im folgenden Renderdurchlauf eingefügt.
    requestAnimationFrame(() => this.historieUeberschrift?.nativeElement.focus());
  }
  async importieren(): Promise<void> {
    const datei = this.datei;
    if (!datei || datei.size === 0 || datei.size > 5 * 1024 * 1024) { this.fehler.set('Bitte eine PDF-Datei mit höchstens 5 MiB auswählen.'); return; }
    await this.ausfuehren(async () => {
      const form = new FormData();
      for (const [name, wert] of Object.entries(this.eingabe)) form.append(name, wert);
      form.append('datei', datei);
      const ziel = this.versionZiel();
      if (ziel) form.append('dokumentVersion', String(ziel.dokumentVersion));
      await firstValueFrom(mitFehlern(this.http.post(this.pfad() + (ziel ? '/' + ziel.id + '/versionen' : ''), form)));
      this.uploadVerwerfen(); this.seite = 0;
      await this.datenLaden(); this.meldung.set(ziel ? 'Neue PDF-Version gespeichert. Frühere Versionen bleiben verfügbar.' : 'PDF-Dokument importiert.');
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
    if (!confirm('Das PDF-Dokument „' + d.titel + '“ mit allen ' + d.dokumentVersion + ' Versionen endgültig löschen?')) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.delete(this.pfad() + '/' + d.id, { params: { dokumentVersion: d.dokumentVersion } })));
      if (this.ausgewaehlt()?.familieId === d.familieId) this.vorschauSchliessen();
      if (this.historieZiel()?.familieId === d.familieId) this.historieZiel.set(null);
      if (this.versionZiel()?.familieId === d.familieId) this.uploadVerwerfen();
      await this.datenLaden(); this.meldung.set('Dokument gelöscht.');
    });
  }
  private pfad(): string { return `/api/mitarbeiter/${this.personId()}/personaldaten/dokumente`; }
  async freigeben(d: Personaldokument): Promise<void> {
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.put(this.pfad() + '/' + d.id + '/freigabe', { freigegeben: !d.selfserviceFreigegeben, version: d.version })));
      await this.datenLaden(); this.meldung.set(d.selfserviceFreigegeben ? 'Freigabe zurückgenommen.' : 'Abrechnung im Mitarbeiter-Self-Service freigegeben.');
    });
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.arbeitetGeaendert.emit(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) { this.fehler.set(fehlertext(e)); }
    finally { this.arbeitet.set(false); if (!this.beendet) this.arbeitetGeaendert.emit(false); }
  }
}
