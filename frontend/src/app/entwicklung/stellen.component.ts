import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { OrganisationApiService } from '../organisation/organisation-api.service';
import { Organisation } from '../shared/model';
import { EntwicklungApi, Entwurf, Profil, Planstelle, Kenntnis, Person, fehlertext, kategorieName } from './entwicklung-api.service';

@Component({ selector: 'app-stellen', standalone: true, imports: [FormsModule, DecimalPipe], templateUrl: './stellen.component.html', styleUrls: ['./entwicklung.css', './stellen.component.css'] })
export class StellenComponent implements OnInit {
  private readonly api = inject(EntwicklungApi);
  private readonly orgApi = inject(OrganisationApiService);
  readonly profile = signal<Profil[]>([]); readonly stellen = signal<Planstelle[]>([]); readonly kenntnisse = signal<Kenntnis[]>([]);
  readonly personen = signal<Person[]>([]); readonly organisationen = signal<Organisation[]>([]);
  readonly fehler = signal(''); readonly meldung = signal(''); readonly arbeitet = signal(false);
  readonly kategorieName = kategorieName;
  readonly detailProfil = signal<Profil | null>(null);
  profil: Entwurf<Profil> = this.leeresProfil(); stelle: Entwurf<Planstelle> = this.leereStelle();
  ngOnInit(): void { void this.ausfuehren(async () => { await this.laden(); }); }
  private leeresProfil(): Entwurf<Profil> { return { name: '', beschreibung: '', aufgaben: '', anforderungen: [] }; }
  private leereStelle(): Entwurf<Planstelle> { return { bezeichnung: '', organisationId: 0, profilId: 0, anteil: 1, mitarbeiterId: null }; }
  async laden(): Promise<void> {
    const [p, s, k, m, o] = await Promise.all([this.api.laden<Profil[]>('profile'), this.api.laden<Planstelle[]>('planstellen'), this.api.laden<Kenntnis[]>('kenntnisse'), this.api.laden<Person[]>('personen'), firstValueFrom(this.orgApi.organisationen())]);
    this.profile.set(p); this.stellen.set(s); this.kenntnisse.set(k); this.personen.set(m); this.organisationen.set(o);
  }
  profilBearbeiten(p?: Profil): void { this.profil = p ? structuredClone(p) : this.leeresProfil(); }
  profilAnzeigen(p: Profil, dialog: HTMLDialogElement): void { this.detailProfil.set(p); dialog.showModal(); }
  stelleBearbeiten(s?: Planstelle): void { this.stelle = s ? { ...s } : this.leereStelle(); }
  async profilSpeichern(): Promise<void> { await this.ausfuehren(async () => { await this.api.speichern<Profil>('profile', this.profil); this.profilBearbeiten(); await this.laden(); }, 'Stellenprofil gespeichert.'); }
  async stelleSpeichern(): Promise<void> { await this.ausfuehren(async () => { await this.api.speichern<Planstelle>('planstellen', this.stelle); this.stelleBearbeiten(); await this.laden(); }, 'Planstelle gespeichert.'); }
  async entfernen(bereich: 'profile' | 'planstellen', d: Profil | Planstelle): Promise<void> {
    if (!confirm('Diesen Eintrag löschen?')) return;
    await this.ausfuehren(async () => { await this.api.loeschen(bereich, d); this.profilBearbeiten(); this.stelleBearbeiten(); await this.laden(); }, 'Eintrag gelöscht.');
  }
  kenntnisName(id: number): string { const k = this.kenntnisse().find(k => k.id === id); return k ? `${k.name} (${kategorieName(k.kategorie)})` : 'Unbekannt'; }
  profilName(id: number): string { return this.profile().find(p => p.id === id)?.name ?? ''; }
  orgName(id: number): string { return this.organisationen().find(o => o.id === id)?.name ?? ''; }
  personName(id: number | null): string { return this.personen().find(p => p.id === id)?.name ?? 'Unbesetzt'; }
  async ausfuehren(aktion: () => Promise<void>, meldung = ''): Promise<void> {
    if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); this.meldung.set(meldung); } catch (e) { this.fehler.set(fehlertext(e)); } finally { this.arbeitet.set(false); }
  }
}
