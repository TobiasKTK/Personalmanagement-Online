import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule, NgForm, NgModel } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiFehler } from '../shared/api-fehler';
import { KontenApiService } from './konten-api.service';
import { Konto } from '../shared/model';
import { SitzungService } from '../sitzung/sitzung.service';

@Component({
  selector: 'app-konten', standalone: true, imports: [FormsModule],
  templateUrl: './konten.component.html',
})
export class KontenComponent implements OnInit {
  private readonly api = inject(KontenApiService);
  readonly session = inject(SitzungService);
  readonly konten = signal<Konto[]>([]);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly arbeitet = signal(false);
  readonly kontonameHinweis = '3–80 Zeichen: nur Kleinbuchstaben (a–z), Ziffern, Punkt, Unterstrich und Bindestrich. Beispiel: personalverwaltung.';
  readonly passwortHinweis = '12–128 Zeichen. Groß- und Kleinschreibung wird berücksichtigt.';
  kontoNeu = true;
  konto: Konto & { passwort: string } = { name: '', rolle: 'LESEN', aktiv: true, passwort: '' };

  ngOnInit(): void { void this.laden(); }
  async laden(): Promise<void> {
    try {
      this.konten.set(await firstValueFrom(this.api.konten()));
    } catch (e) { this.fehler.set(this.text(e)); }
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
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); await this.laden(); this.meldung.set('Änderung gespeichert.'); }
    catch (e) { this.fehler.set(this.text(e)); }
    finally { this.arbeitet.set(false); }
  }
  private text(e: unknown): string {
    if (e instanceof ApiFehler) return [e.message, ...new Set(Object.values(e.felder))].join(' ');
    return e instanceof Error ? e.message : 'Die Änderung konnte nicht gespeichert werden.';
  }
}
