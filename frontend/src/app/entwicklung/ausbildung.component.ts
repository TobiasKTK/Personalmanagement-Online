import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { OrganisationApiService } from '../organisation/organisation-api.service';
import { Organisation } from '../shared/model';
import { EntwicklungApi, Entwurf, Ausbildung, Station, Person, fehlertext } from './entwicklung-api.service';

@Component({ selector: 'app-ausbildung', standalone: true, imports: [FormsModule], templateUrl: './ausbildung.component.html', styleUrl: './entwicklung.css' })
export class AusbildungComponent implements OnInit {
  private readonly api = inject(EntwicklungApi); private readonly orgApi = inject(OrganisationApiService);
  readonly ausbildungen = signal<Ausbildung[]>([]); readonly stationen = signal<Station[]>([]); readonly personen = signal<Person[]>([]); readonly organisationen = signal<Organisation[]>([]);
  readonly fehler = signal(''); readonly meldung = signal(''); readonly arbeitet = signal(false);
  ausbildung: Entwurf<Ausbildung> = this.leereAusbildung(); station: Entwurf<Station> = this.leereStation(); planId = 0;
  readonly statusnamen: Record<string, string> = { GEPLANT: 'Geplant', AKTIV: 'Aktiv', ABGESCHLOSSEN: 'Abgeschlossen', ABGEBROCHEN: 'Abgebrochen' };
  ngOnInit(): void { void this.ausfuehren(async () => { await this.laden(); }); }
  private leereAusbildung(): Entwurf<Ausbildung> { return { mitarbeiterId: 0, beruf: '', institution: '', ausbilderId: null, beginn: '', ende: '', status: 'GEPLANT' }; }
  private leereStation(): Entwurf<Station> { return { ausbildungId: 0, titel: '', lernziele: '', organisationId: null, beginn: '', ende: '', status: 'GEPLANT' }; }
  async laden(): Promise<void> {
    const [a, p, o] = await Promise.all([this.api.laden<Ausbildung[]>('ausbildungen'), this.api.laden<Person[]>('personen'), firstValueFrom(this.orgApi.organisationen())]);
    this.ausbildungen.set(a); this.personen.set(p); this.organisationen.set(o);
  }
  bearbeiten(a?: Ausbildung): void { this.ausbildung = a ? { ...a } : this.leereAusbildung(); }
  stationBearbeiten(s?: Station): void { this.station = s ? { ...s } : { ...this.leereStation(), ausbildungId: this.planId }; }
  async speichern(): Promise<void> { await this.ausfuehren(async () => { await this.api.speichern<Ausbildung>('ausbildungen', this.ausbildung); this.bearbeiten(); await this.laden(); }, 'Ausbildungsverhältnis gespeichert.'); }
  async planLaden(id: number): Promise<void> {
    this.planId = id; this.stationen.set([]); this.stationBearbeiten();
    if (id) await this.ausfuehren(async () => { this.stationen.set(await this.api.laden<Station[]>('stationen', { ausbildungId: id })); });
  }
  async stationSpeichern(): Promise<void> { await this.ausfuehren(async () => {
    await this.api.speichern<Station>('stationen', { ...this.station, ausbildungId: this.planId }); this.stationBearbeiten();
    this.stationen.set(await this.api.laden<Station[]>('stationen', { ausbildungId: this.planId }));
  }, 'Ausbildungsstation gespeichert.'); }
  async entfernen(bereich: 'ausbildungen' | 'stationen', d: Ausbildung | Station): Promise<void> {
    if (!confirm('Diesen Eintrag löschen?')) return;
    await this.ausfuehren(async () => { await this.api.loeschen(bereich, d); this.bearbeiten(); this.stationBearbeiten(); await this.laden();
      if (bereich === 'ausbildungen' && this.planId === d.id) { this.planId = 0; this.stationen.set([]); }
      if (this.planId) this.stationen.set(await this.api.laden<Station[]>('stationen', { ausbildungId: this.planId }));
    }, 'Eintrag gelöscht.');
  }
  personName(id: number | null): string { return this.personen().find(p => p.id === id)?.name ?? 'Nicht zugeordnet'; }
  orgName(id: number | null): string { return this.organisationen().find(o => o.id === id)?.name ?? 'Keine Organisationseinheit'; }
  plan(): Ausbildung | undefined { return this.ausbildungen().find(a => a.id === this.planId); }
  fortschritt(): string { return `${this.stationen().filter(s => s.status === 'ABGESCHLOSSEN').length} von ${this.stationen().length} Stationen abgeschlossen`; }
  async ausfuehren(aktion: () => Promise<void>, meldung = ''): Promise<void> {
    if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); this.meldung.set(meldung); } catch (e) { this.fehler.set(fehlertext(e)); } finally { this.arbeitet.set(false); }
  }
}
