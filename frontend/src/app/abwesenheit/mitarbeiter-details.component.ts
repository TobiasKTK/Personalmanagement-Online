import { AfterViewInit, Component, ElementRef, ViewChild, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { AbwesenheitApiService } from './abwesenheit-api.service';
import { VerlaufApiService } from '../verlauf/verlauf-api.service';
import { Abwesenheit, Mitarbeiter, Verlauf } from '../shared/model';
import { UrlaubskontoComponent } from '../urlaub/urlaubskonto.component';
import { UrlaubsfreigabeComponent } from '../urlaub/urlaubsfreigabe.component';
import { SitzungService } from '../sitzung/sitzung.service';
import { HistorieComponent } from '../mitarbeiter/historie.component';

@Component({ selector: 'app-mitarbeiter-details', standalone: true, imports: [FormsModule, DatePipe, UrlaubskontoComponent, UrlaubsfreigabeComponent, HistorieComponent], templateUrl: './mitarbeiter-details.component.html' })
export class MitarbeiterDetailsComponent implements AfterViewInit {
  private readonly api = inject(AbwesenheitApiService);
  readonly sitzung = inject(SitzungService).sitzung;
  private readonly verlaufApi = inject(VerlaufApiService);
  readonly person = input.required<Mitarbeiter>();
  readonly darfSchreiben = input(false);
  readonly geschlossen = output<void>();
  readonly stammdatenBearbeiten = output<void>();
  readonly personalakteOeffnen = output<void>();
  @ViewChild('dialog') dialog!: ElementRef<HTMLDialogElement>;
  readonly abwesenheiten = signal<Abwesenheit[]>([]);
  readonly verlauf = signal<Verlauf[]>([]);
  readonly fehler = signal('');
  readonly arbeitet = signal(false);
  readonly laedt = signal(false);
  readonly abwesenheitGesamt = signal(0);
  readonly verlaufGesamt = signal(0);
  abwesenheitSeite = 0;
  verlaufSeite = 0;
  bearbeitet?: Abwesenheit;
  eingabe = { art: 'URLAUB', beginn: '', ende: '', halberTag: false };
  private anfrage = 0;
  ngAfterViewInit(): void { this.dialog.nativeElement.showModal(); void this.laden(); }
  schliessen(event?: Event): void {
    event?.preventDefault();
    if (!this.arbeitet()) { this.dialog.nativeElement.close(); this.geschlossen.emit(); }
  }
  bearbeiten(a?: Abwesenheit): void {
    this.bearbeitet = a; this.eingabe = a ? { art: a.art, beginn: a.beginn, ende: a.ende, halberTag: a.halberTag } : { art: 'URLAUB', beginn: '', ende: '', halberTag: false };
  }
  async laden(): Promise<void> {
    const nummer = ++this.anfrage;
    this.laedt.set(true); this.fehler.set('');
    try {
      const [a, v] = await Promise.all([
        firstValueFrom(this.api.abwesenheiten(this.person().id, this.abwesenheitSeite)),
        this.darfSchreiben() ? firstValueFrom(this.verlaufApi.verlauf(this.person().id, this.verlaufSeite)) : Promise.resolve({ eintraege: [], gesamt: 0 }),
      ]);
      if (nummer !== this.anfrage) return;
      if (a.eintraege.length === 0 && this.abwesenheitSeite > 0) { this.abwesenheitSeite--; await this.laden(); return; }
      this.abwesenheiten.set(a.eintraege); this.abwesenheitGesamt.set(a.gesamt);
      this.verlauf.set(v.eintraege); this.verlaufGesamt.set(v.gesamt);
    } catch (e) { if (nummer === this.anfrage) this.fehler.set(this.text(e)); }
    finally { if (nummer === this.anfrage) this.laedt.set(false); }
  }
  async speichern(): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set('');
    try {
      await firstValueFrom(this.api.abwesenheitSpeichern(this.person().id, { ...this.eingabe, version: this.bearbeitet?.version }, this.bearbeitet?.id));
      this.bearbeiten(); this.verlaufSeite = 0; await this.laden();
    } catch (e) { this.fehler.set(this.text(e)); }
    finally { this.arbeitet.set(false); }
  }
  async loeschen(a: Abwesenheit): Promise<void> {
    if (this.arbeitet() || !confirm('Diese Abwesenheit endgültig löschen?')) return;
    this.arbeitet.set(true); this.fehler.set('');
    try { await firstValueFrom(this.api.abwesenheitLoeschen(this.person().id, a)); this.bearbeiten(); this.verlaufSeite = 0; await this.laden(); }
    catch (e) { this.fehler.set(this.text(e)); }
    finally { this.arbeitet.set(false); }
  }
  aenderungen(v: Verlauf): { feld: string; vorher: string; nachher: string }[] {
    const vorher = v.vorher ? JSON.parse(v.vorher) as Record<string, unknown> : {};
    const nachher = v.nachher ? JSON.parse(v.nachher) as Record<string, unknown> : {};
    const namen: Record<string, string> = { nachname: 'Nachname', vorname: 'Vorname', wochenstunden: 'Wochenstunden', organisationseinheit: 'Organisationseinheit', eintrittsdatum: 'Eintritt', austrittsdatum: 'Austritt', geburtsdatum: 'Geburtsdatum', personalnummer: 'Personalnummer', geburtsname: 'Geburtsname', kuerzel: 'Kürzel', beschaeftigungsart: 'Beschäftigungsart', beschaeftigungsstatus: 'Beschäftigungsstatus (Altangabe)', abwesenheitsstatus: 'Abwesenheitsstatus', titel: 'Titel', stelle: 'Stelle', gueltigAb: 'Gültig ab', anspruch: 'Jahresanspruch', uebertrag: 'Übertrag', arbeitstage: 'Arbeitstage (Mo = 1)', feiertage: 'Arbeitsfreie Tage', halberTag: 'Halber Tag', status: 'Antragsstatus', notiz: 'Notiz', entscheidung: 'Entscheidung', konto: 'Mitarbeiterkonto', modus: 'Genehmigungsmodus', genehmiger: 'Genehmiger', art: 'Abwesenheitsart', beginn: 'Beginn', ende: 'Ende' };
    return [...new Set([...Object.keys(vorher), ...Object.keys(nachher)])]
      .filter(k => k in namen && JSON.stringify(vorher[k]) !== JSON.stringify(nachher[k]) && (vorher[k] || nachher[k]))
      .map(k => ({ feld: namen[k], vorher: String(vorher[k] ?? '—'), nachher: String(nachher[k] ?? '—') }));
  }
  aktion(wert: string): string { return wert.replaceAll('_', ' ').toLocaleLowerCase('de-DE'); }
  private text(e: unknown): string { return e instanceof Error ? e.message : 'Die Anfrage ist fehlgeschlagen.'; }
}
