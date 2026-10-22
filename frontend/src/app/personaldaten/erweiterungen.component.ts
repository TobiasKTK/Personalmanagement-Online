import { Component, ElementRef, OnInit, ViewChild, computed, inject, input, output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { mitFehlern, ApiFehler } from '../shared/api-fehler';
import { SitzungService } from '../sitzung/sitzung.service';
import { ErweiterteAkte, Erweiterungen, Fte, Listenname } from './erweiterungen.model';

@Component({ selector: 'app-personaldaten-erweiterungen', standalone: true,
  imports: [FormsModule, DatePipe, DecimalPipe, RouterLink],
  templateUrl: './erweiterungen.component.html', styleUrl: './erweiterungen.component.css' })
export class ErweiterungenComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly sitzung = inject(SitzungService).sitzung;
  readonly personId = input.required<number>();
  readonly arbeitetGeaendert = output<boolean>();
  @ViewChild('editor', { read: ElementRef }) private editor?: ElementRef<HTMLFormElement>;
  readonly daten = signal<Erweiterungen | null>(null);
  readonly fte = signal<Fte | null>(null);
  readonly arbeitet = signal(false);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly istAdmin = computed(() => this.sitzung()?.rolle === 'ADMIN');
  basis: number | null = null;
  private basisOriginal: number | null = null;
  private basisVersion = 0;
  private original = '';
  ngOnInit(): void { void this.laden(); }
  geaendert(): boolean {
    return (!!this.daten() && JSON.stringify(this.daten()) !== this.original)
      || (!!this.fte() && this.basis !== this.basisOriginal);
  }
  async neuLaden(): Promise<void> {
    if (!this.geaendert() || confirm('Ungespeicherte Ergänzungen und die Eingabe zur Vollzeitbasis verwerfen?')) await this.laden();
  }
  async laden(): Promise<void> {
    await this.ausfuehren(async () => {
      const a = await firstValueFrom(mitFehlern(this.http.get<ErweiterteAkte>(this.pfad())));
      this.uebernehmen(a); this.basisUebernehmen(a.fte);
    });
  }
  async fteAktualisieren(): Promise<void> {
    if (!this.daten()) { await this.laden(); return; }
    await this.ausfuehren(async () => {
      const a = await firstValueFrom(mitFehlern(this.http.get<ErweiterteAkte>(this.pfad())));
      const basisGeaendert = this.basis !== this.basisOriginal;
      this.fte.set(a.fte); if (!basisGeaendert) this.basisUebernehmen(a.fte);
    });
  }
  private basisUebernehmen(fte: Fte): void {
    this.basis = fte.vollzeitWochenstunden; this.basisOriginal = fte.vollzeitWochenstunden; this.basisVersion = fte.basisVersion;
  }
  private uebernehmen(a: ErweiterteAkte): void {
    this.daten.set(structuredClone(a.daten)); this.original = JSON.stringify(a.daten); this.fte.set(a.fte);
  }
  hinzufuegen(liste: Listenname): void {
    const d = this.daten(); if (!d || this.arbeitet() || d[liste].length >= 100) return;
    const zeitraum = { beginn: null, ende: null, hinweis: '' };
    switch (liste) {
      case 'kontakte': d.kontakte.push({ art: 'INTERN', name: '', beziehung: '', telefon: '', email: '', prioritaet: 1, hinweis: '' }); break;
      case 'vollmachten': d.vollmachten.push({ ...zeitraum, bezeichnung: '', umfang: '', erteiltDurch: '' }); break;
      case 'kostentraeger': d.kostentraeger.push({ ...zeitraum, nummer: '', bezeichnung: '' }); break;
      case 'beauftragungen': d.beauftragungen.push({ ...zeitraum, aufgabe: '', bereich: '', bestelltDurch: '' }); break;
      case 'bildung': d.bildung.push({ ...zeitraum, art: 'SCHULE', einrichtung: '', fachrichtung: '', abschluss: '', status: 'ABGESCHLOSSEN' }); break;
      case 'fortbildungen': d.fortbildungen.push({ ...zeitraum, titel: '', anbieter: '', dauerStunden: null, status: 'GEPLANT', nachweis: '', gueltigBis: null }); break;
      case 'dienstwagen': case 'dienstraeder': d[liste].push({ ...zeitraum, hersteller: '', modell: '', kennung: '', leasinganbieter: '', monatsrate: null }); break;
    }
    this.meldung.set('');
    setTimeout(() => this.editor?.nativeElement.querySelector<HTMLElement>(`[data-bereich="${liste}"] fieldset:last-of-type input, [data-bereich="${liste}"] fieldset:last-of-type select`)?.focus());
  }
  entfernen(liste: Listenname, index: number): void {
    if (!this.arbeitet() && confirm('Diesen Eintrag entfernen? Die Änderung wird erst beim Speichern übernommen.')) this.daten()![liste].splice(index, 1);
  }
  async speichern(form: NgForm): Promise<void> {
    if (form.invalid) {
      form.form.markAllAsTouched(); this.fehler.set('Bitte die Pflichtfelder sowie Zahlen und E-Mail-Adressen prüfen.');
      const feld = this.editor?.nativeElement.querySelector<HTMLElement>('input.ng-invalid, select.ng-invalid, textarea.ng-invalid');
      const abschnitt = feld?.closest('details'); if (abschnitt) abschnitt.open = true;
      feld?.focus(); return;
    }
    await this.ausfuehren(async () => {
      const a = await firstValueFrom(mitFehlern(this.http.put<ErweiterteAkte>(this.pfad(), this.daten())));
      // Eine noch nicht gespeicherte Eingabe zur Firmenbasis bleibt erhalten.
      const basisGeaendert = this.basis !== this.basisOriginal;
      this.uebernehmen(a); if (!basisGeaendert) this.basisUebernehmen(a.fte);
      this.meldung.set('Ergänzungen gespeichert.');
    });
  }
  async basisSpeichern(form: NgForm): Promise<void> {
    if (form.invalid || !this.istAdmin()) { this.fehler.set('Bitte gültige Vollzeit-Wochenstunden zwischen 0,01 und 168 eingeben.'); return; }
    await this.ausfuehren(async () => {
      const a = await firstValueFrom(mitFehlern(this.http.put<ErweiterteAkte>(this.pfad() + '/vollzeitbasis', { wochenstunden: this.basis, version: this.basisVersion })));
      // Die Basis gilt für die Firma. Lokale, ungespeicherte Mitarbeiterdaten nicht überschreiben.
      this.fte.set(a.fte); this.basisUebernehmen(a.fte);
      this.meldung.set('Vollzeitbasis für die aktuelle Firma gespeichert.');
    });
  }
  private pfad(): string { return `/api/mitarbeiter/${this.personId()}/personaldaten/erweiterungen`; }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.arbeitetGeaendert.emit(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); } catch (e) {
      const details = e instanceof ApiFehler ? Object.values(e.felder).join(' · ') : '';
      this.fehler.set((e instanceof Error ? e.message : 'Die Anfrage ist fehlgeschlagen.') + (details ? ' ' + details : ''));
    } finally { this.arbeitet.set(false); this.arbeitetGeaendert.emit(false); }
  }
}
