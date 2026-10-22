import { Component, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { heutigesDatum } from '../shared/model';
import { Verguetung } from './personaldaten.model';

@Component({ selector: 'app-verguetung', standalone: true, imports: [FormsModule, DatePipe, DecimalPipe], templateUrl: './verguetung.component.html', styleUrl: './personaldaten.component.css' })
export class VerguetungComponent {
  readonly eintraege = input.required<Verguetung[]>();
  readonly readonly = input(false);
  readonly disabled = input(false);
  stichtag = heutigesDatum();
  readonly basen = [{ id: 'MONAT', name: 'pro Monat' }, { id: 'STUNDE', name: 'pro Stunde' }, { id: 'JAHR', name: 'pro Jahr' }, { id: 'EINMALIG', name: 'einmalig' }];
  basis(id: string): string { return this.basen.find(b => b.id === id)?.name ?? id; }
  aktuell(): Verguetung[] {
    const staende = new Map<string, Verguetung>();
    for (const v of this.eintraege().filter(v => v.gueltigAb <= this.stichtag).sort((a, b) => a.gueltigAb.localeCompare(b.gueltigAb))) staende.set(v.lohnart.toLowerCase(), v);
    return [...staende.values()].filter(v => v.basis !== 'EINMALIG' || v.gueltigAb === this.stichtag);
  }
  hinzufuegen(): void { this.eintraege().push({ gueltigAb: heutigesDatum(), lohnart: '', bezeichnung: '', betrag: 0, waehrung: 'EUR', basis: 'MONAT' }); }
}
