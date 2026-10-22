import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule, NgForm, NgModel } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
import { KontenApiService } from './konten-api.service';
import { Konto } from '../shared/model';
import { SitzungService } from '../sitzung/sitzung.service';

interface Firma { mandantenspeicherId: number; name: string; status: string; }
interface Firmenzugang { mandantenspeicherId: number; firma: string; rolle: string; aktiv: boolean; }
interface Benutzerkonto { name: string; aktiv: boolean; systemverwaltung: boolean; zuordnungen: Firmenzugang[]; }

@Component({
  selector: 'app-konten', standalone: true, imports: [FormsModule, RouterLink],
  templateUrl: './konten.component.html',
})
export class KontenComponent implements OnInit {
  private readonly api = inject(KontenApiService);
  private readonly http = inject(HttpClient);
  readonly session = inject(SitzungService);
  readonly firmenadmin = computed(() => this.session.sitzung()?.mandantenspeicherId != null && this.session.sitzung()?.rolle === 'ADMIN');
  readonly konten = signal<Konto[]>([]);
  readonly benutzer = signal<Benutzerkonto[]>([]);
  readonly firmen = signal<Firma[]>([]);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly arbeitet = signal(false);
  readonly kontonameHinweis = '3–80 Zeichen: nur Kleinbuchstaben (a–z), Ziffern, Punkt, Unterstrich und Bindestrich. Beispiel: personalverwaltung.';
  readonly passwortHinweis = '12–128 Zeichen. Groß- und Kleinschreibung wird berücksichtigt.';
  kontoNeu = true;
  konto: Konto & { passwort: string } = { name: '', rolle: 'LESEN', aktiv: true, passwort: '' };
  globalesKonto: (Omit<Benutzerkonto, 'zuordnungen'> & { passwort: string }) | null = null;
  zugang = { mandantenspeicherId: null as number | null, rolle: 'LESEN', aktiv: true };

  ngOnInit(): void { void this.ausfuehren(async () => {}, ''); }
  private async laden(): Promise<void> {
    if (this.session.sitzung()?.systemverwaltung) {
      const [benutzer, firmen] = await Promise.all([
        firstValueFrom(mitFehlern(this.http.get<Benutzerkonto[]>('/api/mandantenverwaltung/konten'))),
        firstValueFrom(mitFehlern(this.http.get<Firma[]>('/api/mandantenverwaltung'))),
      ]);
      this.benutzer.set(benutzer);
      this.firmen.set(firmen.filter(f => f.status === 'AKTIV'));
    } else {
      this.konten.set(await firstValueFrom(this.api.konten()));
    }
  }
  kontoBearbeiten(k?: Konto, formular?: NgForm): void {
    this.kontoNeu = !k;
    this.konto = k ? { ...k, passwort: '' } : { name: '', rolle: 'LESEN', aktiv: true, passwort: '' };
    formular?.resetForm(this.konto);
    this.fehler.set(''); this.meldung.set('');
  }
  kontoFeldFehler(feld: NgModel): string {
    if (!feld.touched || !feld.invalid) return '';
    if (feld.name === 'name') return feld.hasError('required') ? 'Bitte einen Kontonamen eingeben.' : this.kontonameHinweis;
    if (feld.name === 'passwort') return feld.hasError('required') ? 'Bitte ein Passwort eingeben.' : this.passwortHinweis;
    return 'Bitte eine gültige Eingabe machen.';
  }
  async kontoSpeichern(formular: NgForm): Promise<void> {
    if (this.arbeitet()) return;
    this.fehler.set(''); this.meldung.set('');
    formular.form.markAllAsTouched();
    if (formular.invalid) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(this.api.kontoSpeichern(this.konto, this.kontoNeu));
      this.kontoBearbeiten(undefined, formular);
    });
  }
  benutzerBearbeiten(k: Benutzerkonto, zugang?: Firmenzugang): void {
    this.globalesKonto = { name: k.name, aktiv: k.aktiv, systemverwaltung: k.systemverwaltung, passwort: '' };
    this.zugang.mandantenspeicherId = zugang?.mandantenspeicherId ?? this.session.sitzung()?.mandantenspeicherId ?? null;
    this.zugangFirmaWaehlen();
    this.fehler.set(''); this.meldung.set('');
  }
  zugangFirmaWaehlen(): void {
    const vorhanden = this.benutzer().find(k => k.name === this.globalesKonto?.name)
      ?.zuordnungen.find(z => z.mandantenspeicherId === this.zugang.mandantenspeicherId);
    this.zugang = { mandantenspeicherId: this.zugang.mandantenspeicherId, rolle: vorhanden?.rolle ?? 'LESEN', aktiv: vorhanden?.aktiv ?? true };
  }
  async benutzerSpeichern(formular: NgForm): Promise<void> {
    formular.form.markAllAsTouched();
    if (formular.invalid || !this.globalesKonto) return;
    const bearbeitet = this.globalesKonto;
    const { name, aktiv, systemverwaltung, passwort } = bearbeitet;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.put('/api/mandantenverwaltung/konten/' + encodeURIComponent(name), { aktiv, systemverwaltung, passwort })));
      bearbeitet.passwort = '';
      formular.resetForm({ globalAktiv: aktiv, systemverwaltung, globalesPasswort: '' });
    });
  }
  async zugangSpeichern(formular: NgForm): Promise<void> {
    formular.form.markAllAsTouched();
    if (formular.invalid || !this.globalesKonto || !this.zugang.mandantenspeicherId) return;
    const { name } = this.globalesKonto;
    const { mandantenspeicherId, rolle, aktiv } = this.zugang;
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.put('/api/mandantenverwaltung/' + mandantenspeicherId + '/konten', { name, rolle, aktiv })));
      formular.resetForm({ zugangFirma: mandantenspeicherId, zugangRolle: rolle, zugangAktiv: aktiv });
    }, 'Firmenzugang gespeichert. Betroffene Konten müssen sich erneut anmelden.');
  }
  private async ausfuehren(aktion: () => Promise<void>, meldung = 'Änderung gespeichert.'): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); await this.laden(); this.meldung.set(meldung); }
    catch (e) { this.fehler.set(this.text(e)); }
    finally { this.arbeitet.set(false); }
  }
  private text(e: unknown): string {
    if (e instanceof ApiFehler) return [e.message, ...new Set(Object.values(e.felder))].join(' ');
    return e instanceof Error ? e.message : 'Die Änderung konnte nicht gespeichert werden.';
  }
}
