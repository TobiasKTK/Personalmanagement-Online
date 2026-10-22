import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { SitzungService } from '../sitzung/sitzung.service';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
import { heutigesDatum, Mitarbeiter, MitarbeiterSeite } from '../shared/model';

interface Vorlagenaufgabe { titel: string; tage: number; }
interface Vorlage { id: number; typ: string; titel: string; version: number; aufgaben: Vorlagenaufgabe[]; }
interface Aufgabe {
  id: number; vorgangId: number; mitarbeiterId: number; mitarbeiterName: string; typ: string;
  vorgangTitel: string; titel: string; verantwortlich: string; faelligAm: string; status: string; version: number;
}
@Component({
  selector: 'app-personalprozesse', standalone: true, imports: [FormsModule, DatePipe, RouterLink],
  templateUrl: './personalprozesse.component.html', styleUrl: './personalprozesse.component.css',
})
export class PersonalprozesseComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private navigation = 0;
  private ladenAusstehend = false;
  private readonly statusEntwuerfe = new Map<number, { status: string; version: number }>();
  readonly sitzung = inject(SitzungService).sitzung;
  readonly aufgaben = signal<Aufgabe[]>([]);
  readonly vorlagen = signal<Vorlage[]>([]);
  readonly konten = signal<string[]>([]);
  readonly mitarbeiter = signal<Mitarbeiter[]>([]);
  readonly arbeitet = signal(false);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly gesamt = signal(0);
  readonly heute = heutigesDatum();
  readonly statusTexte: Record<string, string> = { OFFEN: 'Offen', IN_BEARBEITUNG: 'In Bearbeitung', ERLEDIGT: 'Erledigt' };
  status = 'ALLE'; seite = 0; fokusId: number | null = null;
  suche = '';
  start = { vorlageId: null as number | null, mitarbeiterId: null as number | null, bezugsdatum: '', verantwortlich: '' };
  entwurf = { id: null as number | null, version: null as number | null, typ: 'ONBOARDING', titel: '', aufgaben: [{ titel: '', tage: 0 }] as Vorlagenaufgabe[] };
  zuordnung: { aufgabe: Aufgabe; verantwortlich: string; faelligAm: string } | null = null;
  get verwalten(): boolean { return this.sitzung()?.rolle === 'ADMIN' || this.sitzung()?.rolle === 'PERSONAL'; }
  typText(typ: string): string { return typ === 'ONBOARDING' ? 'Onboarding' : 'Offboarding'; }
  ngOnInit(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      ++this.navigation;
      const id = Number(params.get('aufgabe')); this.fokusId = Number.isSafeInteger(id) && id > 0 ? id : null;
      void this.laden();
    });
  }
  async laden(): Promise<void> {
    if (this.arbeitet()) { this.ladenAusstehend = true; return; }
    await this.ausfuehren(async () => {
      if (this.verwalten) {
        const [vorlagen, konten] = await Promise.all([
          firstValueFrom(mitFehlern(this.http.get<Vorlage[]>('/api/personalprozesse/vorlagen'))),
          firstValueFrom(mitFehlern(this.http.get<string[]>('/api/personalprozesse/konten'))),
        ]);
        this.vorlagen.set(vorlagen); this.konten.set(konten);
        this.start.vorlageId ??= vorlagen[0]?.id ?? null;
        this.start.verantwortlich ||= konten.includes(this.sitzung()?.name ?? '') ? this.sitzung()!.name : '';
      }
      await this.aufgabenLaden();
    });
  }
  private async aufgabenLaden(): Promise<void> {
    const navigation = this.navigation;
    try {
      const daten = this.fokusId
        ? { eintraege: [await firstValueFrom(mitFehlern(this.http.get<Aufgabe>('/api/personalprozesse/aufgaben/' + this.fokusId)))], gesamt: 1 }
        : await firstValueFrom(mitFehlern(this.http.get<{ eintraege: Aufgabe[]; gesamt: number }>('/api/personalprozesse/aufgaben', { params: { status: this.status, seite: this.seite } })));
      if (navigation !== this.navigation || this.destroyRef.destroyed) return;
      this.aufgaben.set(daten.eintraege); this.gesamt.set(daten.gesamt);
      if (!this.fokusId && !daten.eintraege.length && this.seite > 0) { this.seite--; await this.aufgabenLaden(); }
    } catch (e) {
      if (navigation === this.navigation && !this.destroyRef.destroyed) throw e;
    }
  }
  async aktualisieren(): Promise<void> {
    if (this.arbeitet()) return;
    if ((this.statusEntwuerfe.size || this.zuordnung) && !confirm('Ungespeicherte Aufgabenänderungen verwerfen und neu laden?')) return;
    this.statusEntwuerfe.clear(); this.zuordnung = null;
    await this.laden();
  }
  async filtern(seite = 0): Promise<void> { if (this.arbeitet()) return; this.seite = seite; this.fokusId = null; await this.laden(); }
  async suchen(): Promise<void> {
    await this.ausfuehren(async () => {
      const daten = await firstValueFrom(mitFehlern(this.http.get<MitarbeiterSeite>('/api/mitarbeiter', { params: { suche: this.suche, groesse: 100 } })));
      this.mitarbeiter.set(daten.mitarbeiter); this.start.mitarbeiterId = null;
      if (daten.gesamt > daten.mitarbeiter.length) this.meldung.set('Bitte die Suche eingrenzen. Die ersten 100 Treffer werden angezeigt.');
      else if (!daten.gesamt) this.meldung.set('Keine Mitarbeiter zur Suche gefunden.');
    });
  }
  async starten(): Promise<void> {
    const vorlage = this.vorlagen().find(v => v.id === this.start.vorlageId);
    if (!vorlage) { this.fehler.set('Bitte eine Vorlage auswählen.'); return; }
    const navigation = this.navigation;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.post('/api/personalprozesse/vorgaenge', { ...this.start, vorlageVersion: vorlage.version })));
      if (navigation === this.navigation) { this.fokusId = null; this.status = 'ALLE'; this.seite = 0; }
      this.start.mitarbeiterId = null; this.start.bezugsdatum = '';
      await this.aufgabenLaden(); this.meldung.set('Vorgang gestartet. Die Verantwortlichen wurden benachrichtigt.');
    });
  }
  neueVorlage(): void { this.entwurf = { id: null, version: null, typ: 'ONBOARDING', titel: '', aufgaben: [{ titel: '', tage: 0 }] }; }
  bearbeiten(v: Vorlage): void { this.entwurf = { ...v, aufgaben: v.aufgaben.map(a => ({ ...a })) }; }
  async vorlageSpeichern(): Promise<void> {
    await this.ausfuehren(async () => {
      const e = this.entwurf;
      const gespeichert = await firstValueFrom(mitFehlern(e.id === null
        ? this.http.post<Vorlage>('/api/personalprozesse/vorlagen', e)
        : this.http.put<Vorlage>('/api/personalprozesse/vorlagen/' + e.id, e)));
      this.vorlagen.set(await firstValueFrom(mitFehlern(this.http.get<Vorlage[]>('/api/personalprozesse/vorlagen'))));
      this.bearbeiten(gespeichert); this.meldung.set('Vorlage gespeichert.');
    });
  }
  async statusSpeichern(a: Aufgabe): Promise<void> {
    await this.ausfuehren(async () => {
      const entwurf = this.statusEntwuerfe.get(a.id) ?? { status: a.status, version: a.version };
      const gespeichert = await firstValueFrom(mitFehlern(this.http.put<Aufgabe>('/api/personalprozesse/aufgaben/' + a.id + '/status', entwurf)));
      this.statusEntwuerfe.delete(a.id);
      // Nur die bestätigte eigene Änderung darf den Token eines offenen Entwurfs fortschreiben.
      if (this.zuordnung?.aufgabe.id === a.id && this.zuordnung.aufgabe.version === entwurf.version) this.zuordnung.aufgabe = gespeichert;
      await this.aufgabenLaden(); this.meldung.set('Aufgabenstatus gespeichert.');
    });
  }
  statusEntwurf(a: Aufgabe): string { return this.statusEntwuerfe.get(a.id)?.status ?? a.status; }
  statusAendern(a: Aufgabe, status: string): void {
    if (status === a.status) this.statusEntwuerfe.delete(a.id);
    else this.statusEntwuerfe.set(a.id, { status, version: this.statusEntwuerfe.get(a.id)?.version ?? a.version });
  }
  async zuordnungSpeichern(): Promise<void> {
    const z = this.zuordnung; if (!z) return;
    await this.ausfuehren(async () => {
      const gespeichert = await firstValueFrom(mitFehlern(this.http.put<Aufgabe>('/api/personalprozesse/aufgaben/' + z.aufgabe.id + '/zuordnung', { verantwortlich: z.verantwortlich, faelligAm: z.faelligAm, version: z.aufgabe.version })));
      const status = this.statusEntwuerfe.get(z.aufgabe.id);
      if (status?.version === z.aufgabe.version) this.statusEntwuerfe.set(z.aufgabe.id, { ...status, version: gespeichert.version });
      this.zuordnung = null; await this.aufgabenLaden(); this.meldung.set('Zuständigkeit und Fälligkeit gespeichert.');
    });
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); }
    catch (e) { this.fehler.set(e instanceof ApiFehler ? [e.message, ...Object.values(e.felder)].join(' ') : e instanceof Error ? e.message : 'Anfrage fehlgeschlagen.'); }
    finally {
      this.arbeitet.set(false);
      if (this.ladenAusstehend && !this.destroyRef.destroyed) {
        // Auch Zurück/Vorwärts kann während einer laufenden Anfrage die Ansicht wechseln.
        this.ladenAusstehend = false;
        await this.laden();
      }
    }
  }
}
