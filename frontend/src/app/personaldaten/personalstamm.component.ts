import { Component, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Person } from '../entwicklung/entwicklung-api.service';
import { heutigesDatum } from '../shared/model';
import { Personaldaten } from './personaldaten.model';

@Component({ selector: 'app-personalstamm', standalone: true, imports: [FormsModule], templateUrl: './personalstamm.component.html', styleUrl: './personaldaten.component.css' })
export class PersonalstammComponent {
  readonly daten = input.required<Personaldaten>();
  readonly personen = input<Person[]>([]);
  readonly personId = input.required<number>();
  readonly readonly = input(false);
  readonly disabled = input(false);
  kostenstelle(): void { this.daten().kostenstellen.push({ nummer: '', bezeichnung: '', anteil: this.daten().kostenstellen.length ? 0 : 100 }); }
  vertretung(): void { this.daten().vertretungen.push({ personId: 0, aufgabe: '', beginn: heutigesDatum(), ende: null }); }
}
