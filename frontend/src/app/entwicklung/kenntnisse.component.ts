import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EntwicklungApi, Entwurf, Kenntnis, Nachweis, Person, Profil, Abgleich, KATEGORIEN, kategorieName, fehlertext, heute } from './entwicklung-api.service';

@Component({ selector: 'app-kenntnisse', standalone: true, imports: [FormsModule], templateUrl: './kenntnisse.component.html', styleUrl: './entwicklung.css' })
export class KenntnisseComponent implements OnInit {
  private readonly api = inject(EntwicklungApi);
  readonly katalog = signal<Kenntnis[]>([]); readonly personen = signal<Person[]>([]); readonly profile = signal<Profil[]>([]);
  readonly nachweise = signal<Nachweis[]>([]); readonly ergebnis = signal<Abgleich[]>([]); readonly abgeglichen = signal(false);
  readonly fehler = signal(''); readonly meldung = signal(''); readonly arbeitet = signal(false);
  readonly kategorien = KATEGORIEN; readonly kategorieName = kategorieName;
  kenntnis: Entwurf<Kenntnis> = { name: '', kategorie: 'FACHKENNTNIS' };
  nachweis: Entwurf<Nachweis> = this.leererNachweis();
  personId = 0; profilId: number | null = null; stichtag = heute();
  ngOnInit(): void { void this.ausfuehren(async () => { await this.laden(); }); }
  private leererNachweis(): Entwurf<Nachweis> { return { mitarbeiterId: 0, kenntnisId: 0, niveau: 1, erworbenAm: heute(), gueltigBis: null, nachweis: '' }; }
  async laden(): Promise<void> {
    const [k, p, s] = await Promise.all([this.api.laden<Kenntnis[]>('kenntnisse'), this.api.laden<Person[]>('personen'), this.api.laden<Profil[]>('profile')]);
    this.katalog.set(k); this.personen.set(p); this.profile.set(s);
  }
  kenntnisBearbeiten(k?: Kenntnis): void { this.kenntnis = k ? { ...k } : { name: '', kategorie: 'FACHKENNTNIS' }; }
  nachweisBearbeiten(n?: Nachweis): void { this.nachweis = n ? { ...n } : { ...this.leererNachweis(), mitarbeiterId: this.personId }; }
  async personWechseln(): Promise<void> {
    this.nachweisBearbeiten(); this.nachweise.set([]); this.ergebnis.set([]); this.abgeglichen.set(false);
    if (this.personId) await this.ausfuehren(async () => { this.nachweise.set(await this.api.laden<Nachweis[]>('nachweise', { mitarbeiterId: this.personId })); });
  }
  async kenntnisSpeichern(): Promise<void> { await this.ausfuehren(async () => { await this.api.speichern<Kenntnis>('kenntnisse', this.kenntnis); this.kenntnisBearbeiten(); await this.laden(); }, 'Kenntnis gespeichert.'); }
  async nachweisSpeichern(): Promise<void> {
    await this.ausfuehren(async () => {
      await this.api.speichern<Nachweis>('nachweise', { ...this.nachweis, mitarbeiterId: this.personId, gueltigBis: this.nachweis.gueltigBis || null });
      this.nachweisBearbeiten(); this.nachweise.set(await this.api.laden<Nachweis[]>('nachweise', { mitarbeiterId: this.personId })); this.abgeglichen.set(false);
    }, 'Kenntnisnachweis gespeichert.');
  }
  async abgleichen(): Promise<void> {
    await this.ausfuehren(async () => {
      this.abgeglichen.set(false);
      const params: Record<string, string | number> = { mitarbeiterId: this.personId, stichtag: this.stichtag };
      if (this.profilId !== null) params['profilId'] = this.profilId;
      this.ergebnis.set(await this.api.laden<Abgleich[]>('abgleich', params)); this.abgeglichen.set(true);
    });
  }
  async entfernen(bereich: 'kenntnisse' | 'nachweise', d: Kenntnis | Nachweis): Promise<void> {
    if (!confirm('Diesen Eintrag löschen?')) return;
    await this.ausfuehren(async () => { await this.api.loeschen(bereich, d); await this.laden(); this.kenntnisBearbeiten(); this.nachweisBearbeiten();
      if (this.personId) this.nachweise.set(await this.api.laden<Nachweis[]>('nachweise', { mitarbeiterId: this.personId })); this.abgeglichen.set(false);
    }, 'Eintrag gelöscht.');
  }
  kenntnisName(id: number): string { return this.katalog().find(k => k.id === id)?.name ?? ''; }
  bedarf(): number { return this.ergebnis().filter(a => a.differenz > 0).length; }
  async ausfuehren(aktion: () => Promise<void>, meldung = ''): Promise<void> {
    if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); this.meldung.set(meldung); } catch (e) { this.fehler.set(fehlertext(e)); } finally { this.arbeitet.set(false); }
  }
}
