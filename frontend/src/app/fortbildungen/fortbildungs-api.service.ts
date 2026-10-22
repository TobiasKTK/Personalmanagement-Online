import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';

export interface Teilnahme {
  mitarbeiterId: number; name: string; personalnummer: number; abgeschlossen: boolean;
  nachweisId: number | null; niveau: number | null; erworbenAm: string | null; gueltigBis: string | null; nachweis: string | null;
}
export interface Schulungsdaten { titel: string; anbieter: string; beginn: string; ende: string; kenntnisId: number; niveau: number; }
export interface Schulung extends Schulungsdaten { id: number; version: number; teilnahmen: Teilnahme[]; }
export interface Schulungsentwurf extends Schulungsdaten { id?: number; version?: number; }
export interface Abschluss { version: number; erworbenAm: string; gueltigBis: string | null; nachweis: string; vorhandenerNachweisId: number | null; nachweisVersion: number | null; }

@Injectable({ providedIn: 'root' })
export class FortbildungsApi {
  private readonly http = inject(HttpClient);
  private readonly basis = '/api/entwicklung/schulungen';
  liste(): Promise<Schulung[]> { return firstValueFrom(mitFehlern(this.http.get<Schulung[]>(this.basis))); }
  laden(id: number): Promise<Schulung> { return firstValueFrom(mitFehlern(this.http.get<Schulung>(`${this.basis}/${id}`))); }
  speichern(e: Schulungsentwurf): Promise<Schulung> {
    const { id, ...daten } = e;
    return firstValueFrom(mitFehlern(id ? this.http.put<Schulung>(`${this.basis}/${id}`, daten) : this.http.post<Schulung>(this.basis, daten)));
  }
  zuordnen(s: Schulung, mitarbeiterIds: number[]): Promise<Schulung> {
    return firstValueFrom(mitFehlern(this.http.post<Schulung>(`${this.basis}/${s.id}/teilnahmen`, { version: s.version, mitarbeiterIds })));
  }
  entfernen(s: Schulung, person: number): Promise<Schulung> {
    return firstValueFrom(mitFehlern(this.http.delete<Schulung>(`${this.basis}/${s.id}/teilnahmen/${person}`, { params: { version: s.version } })));
  }
  abschliessen(s: Schulung, person: number, e: Abschluss): Promise<Schulung> {
    return firstValueFrom(mitFehlern(this.http.post<Schulung>(`${this.basis}/${s.id}/teilnahmen/${person}/abschluss`, e)));
  }
  loeschen(s: Schulung): Promise<void> { return firstValueFrom(mitFehlern(this.http.delete<void>(`${this.basis}/${s.id}`, { params: { version: s.version } }))); }
}
