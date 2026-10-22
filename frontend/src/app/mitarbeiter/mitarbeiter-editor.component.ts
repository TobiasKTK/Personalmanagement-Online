import { AfterViewInit, Component, DestroyRef, ElementRef, HostListener, OnInit, ViewChild, inject, input, output, signal } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MitarbeiterApiService } from './mitarbeiter-api.service';
import { ApiFehler } from '../shared/api-fehler';
import { BESCHAEFTIGUNGSART, Mitarbeiter, MitarbeiterEingabe, Organisation } from '../shared/model';

@Component({
  selector: 'app-mitarbeiter-editor',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './mitarbeiter-editor.component.html',
})
export class MitarbeiterEditorComponent implements OnInit, AfterViewInit {
  private readonly api = inject(MitarbeiterApiService);
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly destroyRef = inject(DestroyRef);
  private anfang!: ReturnType<typeof this.formular.getRawValue>;
  private vorgeschlageneNummer: number | null = null;
  private navigationEntscheiden?: (verlassen: boolean) => void;

  readonly person = input<Mitarbeiter | null>(null);
  readonly organisationen = input<Organisation[]>([]);
  readonly gespeichert = output<Mitarbeiter>();
  readonly ergaenzen = output<Mitarbeiter>();
  readonly geschlossen = output<void>();
  readonly speichert = signal(false);
  readonly fehler = signal('');
  readonly feldfehler = signal<Record<string, string>>({});
  readonly nummerHinweis = signal('Beim Speichern fest vergeben.');
  readonly formular = this.fb.group({
    personalnummer: new FormControl<number | null>(null, [Validators.min(1), Validators.max(2147483647)]),
    titel: ['', Validators.maxLength(80)],
    nachname: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(120)]],
    vorname: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(120)]],
    geburtsname: ['', Validators.maxLength(120)],
    kuerzel: ['', Validators.maxLength(20)],
    geburtsdatum: [''],
    eintrittsdatum: ['', Validators.required],
    austrittsdatum: [''],
    wochenstunden: new FormControl<number | null>(null, [Validators.required, Validators.min(0.5), Validators.max(168)]),
    organisationseinheitId: new FormControl<number | null>(null),
    stelle: ['', Validators.maxLength(120)],
    beschaeftigungsart: ['', Validators.maxLength(80)],
  });

  @ViewChild('editor') private editor!: ElementRef<HTMLDialogElement>;
  @ViewChild('bestaetigung') private bestaetigung!: ElementRef<HTMLDialogElement>;
  @ViewChild('formularElement') private formularElement!: ElementRef<HTMLFormElement>;

  constructor() {
    this.destroyRef.onDestroy(() => this.navigationAbschliessen(false));
  }

  ngOnInit(): void {
    const person = this.person();
    if (person) {
      this.formular.patchValue({
        ...person,
        geburtsdatum: person.geburtsdatum ?? '',
        austrittsdatum: person.austrittsdatum ?? '',
      });
      this.nummerHinweis.set('Die Personalnummer kann nach der Neuanlage nicht geändert werden.');
    }
    this.anfang = this.formular.getRawValue();
  }

  ngAfterViewInit(): void {
    this.editor.nativeElement.showModal();
    this.formularElement.nativeElement.querySelector<HTMLInputElement>('#nachname')?.focus();
    if (!this.person()) void this.nummerVorschlagen();
  }

  beschaeftigungsart(): readonly string[] {
    return this.statusoptionen(BESCHAEFTIGUNGSART, this.person()?.beschaeftigungsart);
  }


  feldFehler(name: keyof typeof this.formular.controls): string {
    const serverfehler = this.feldfehler()[name];
    if (serverfehler) return serverfehler;
    const feld = this.formular.controls[name];
    if (!feld.touched || !feld.invalid) return '';
    if (feld.hasError('required') || feld.hasError('pattern')) return 'Bitte füllen Sie dieses Feld aus.';
    if (feld.hasError('maxlength')) return 'Die Eingabe ist zu lang.';
    return 'Bitte geben Sie einen gültigen Wert ein.';
  }

  schliessen(event?: Event): void {
    event?.preventDefault();
    if (this.speichert()) return;
    if (this.hatAenderungen()) {
      this.bestaetigung.nativeElement.showModal();
    } else {
      this.verwerfen();
    }
  }

  verlassenErlauben(): boolean | Promise<boolean> {
    if (this.speichert()) return false;
    if (!this.hatAenderungen()) return true;
    this.navigationAbschliessen(false);
    return new Promise(resolve => {
      this.navigationEntscheiden = resolve;
      this.bestaetigung.nativeElement.showModal();
    });
  }

  @HostListener('window:beforeunload', ['$event'])
  vorSeitenwechsel(event: BeforeUnloadEvent): void {
    if (this.speichert() || this.hatAenderungen()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  private hatAenderungen(): boolean {
    const ungueltigeZahl = Array.from(this.formularElement.nativeElement.querySelectorAll('input'))
      .some(feld => feld.validity.badInput);
    return ungueltigeZahl || JSON.stringify(this.formular.getRawValue()) !== JSON.stringify(this.anfang);
  }

  private navigationAbschliessen(verlassen: boolean): void {
    const entscheiden = this.navigationEntscheiden;
    this.navigationEntscheiden = undefined;
    entscheiden?.(verlassen);
  }

  weiterBearbeiten(event?: Event): void {
    event?.preventDefault();
    this.bestaetigung.nativeElement.close();
    this.navigationAbschliessen(false);
  }

  verwerfen(): void {
    this.bestaetigung.nativeElement.close();
    this.editor.nativeElement.close();
    this.navigationAbschliessen(true);
    this.geschlossen.emit();
  }

  vorSchliessenSpeichern(): void {
    this.bestaetigung.nativeElement.close();
    // Auch bei ungültigen Pflichtfeldern muss eine wartende Navigation beendet werden.
    void this.speichern();
  }

  async speichern(weitereDaten = false): Promise<void> {
    if (this.speichert()) return;
    this.fehler.set('');
    this.feldfehler.set({});
    this.formular.markAllAsTouched();
    if (!this.formularElement.nativeElement.reportValidity() || this.formular.invalid) {
      this.formularElement.nativeElement.querySelector<HTMLInputElement>('.ng-invalid')?.focus();
      this.navigationAbschliessen(false);
      return;
    }
    const werte = this.formular.getRawValue();
    const person = this.person();
    const eingabe: MitarbeiterEingabe = {
      personalnummer: !person && werte.personalnummer === this.vorgeschlageneNummer ? null : werte.personalnummer,
      titel: werte.titel.trim(),
      nachname: werte.nachname.trim(),
      vorname: werte.vorname.trim(),
      geburtsname: werte.geburtsname.trim(),
      kuerzel: werte.kuerzel.trim(),
      geburtsdatum: werte.geburtsdatum || null,
      eintrittsdatum: werte.eintrittsdatum,
      austrittsdatum: werte.austrittsdatum || null,
      wochenstunden: werte.wochenstunden!,
      organisationseinheit: '',
      organisationseinheitId: werte.organisationseinheitId,
      stelle: werte.stelle.trim(),
      beschaeftigungsart: werte.beschaeftigungsart,
      ...(person ? { version: person.version, datenGueltigAb: person.datenGueltigAb } : {}),
    };
    this.speichert.set(true);
    this.formular.disable();
    try {
      const gespeichert = await firstValueFrom(this.api.speichern(eingabe, person?.id));
      this.editor.nativeElement.close();
      this.navigationAbschliessen(true);
      if (weitereDaten && !person) this.ergaenzen.emit(gespeichert);
      else this.gespeichert.emit(gespeichert);
    } catch (fehler) {
      this.fehler.set(fehler instanceof Error ? fehler.message : 'Speichern fehlgeschlagen.');
      if (fehler instanceof ApiFehler) this.feldfehler.set(fehler.felder);
      this.navigationAbschliessen(false);
    } finally {
      this.formular.enable();
      this.speichert.set(false);
    }
  }

  private async nummerVorschlagen(): Promise<void> {
    try {
      const vorschlag = await firstValueFrom(this.api.naechstePersonalnummer());
      if (this.destroyRef.destroyed || this.speichert()) return;
      const feld = this.formular.controls.personalnummer;
      if (feld.pristine && feld.value === null) {
        this.vorgeschlageneNummer = vorschlag.personalnummer;
        feld.setValue(vorschlag.personalnummer);
        // Ein automatisch geladener Vorschlag ist keine Änderung durch den Benutzer.
        this.anfang.personalnummer = vorschlag.personalnummer;
      }
    } catch {
      if (!this.destroyRef.destroyed) this.nummerHinweis.set('Die Personalnummer wird beim Speichern automatisch vergeben.');
    }
  }

  private statusoptionen(optionen: readonly string[], bisher?: string): readonly string[] {
    return bisher && !optionen.includes(bisher) ? [...optionen, bisher] : optionen;
  }
}
