import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';

export interface Datensatz { id: number; version: number; }
export type Entwurf<T extends Datensatz> = Omit<T, 'id' | 'version'> & Partial<Datensatz>;
export interface Person { id: number; name: string; personalnummer: number; }
export interface Kenntnis extends Datensatz { name: string; kategorie: string; }
export interface Anforderung { kenntnisId: number; niveau: number; }
export interface Profil extends Datensatz { name: string; beschreibung: string; aufgaben: string; anforderungen: Anforderung[]; }
export interface Planstelle extends Datensatz { bezeichnung: string; organisationId: number; profilId: number; anteil: number; mitarbeiterId: number | null; }
export interface Nachweis extends Datensatz { mitarbeiterId: number; kenntnisId: number; niveau: number; erworbenAm: string; gueltigBis: string | null; nachweis: string; }
export interface Abgleich { kenntnisId: number; name: string; kategorie: string; soll: number; ist: number; differenz: number; grund: string; }
export interface Ausbildung extends Datensatz { mitarbeiterId: number; beruf: string; institution: string; ausbilderId: number | null; beginn: string; ende: string; status: string; }
export interface Station extends Datensatz { ausbildungId: number; titel: string; lernziele: string; organisationId: number | null; beginn: string; ende: string; status: string; }
export const KATEGORIEN = [
  { wert: 'FACHKENNTNIS', name: 'Fachkenntnisse' }, { wert: 'KOMPETENZ', name: 'Kompetenzen' },
  { wert: 'SPRACHE', name: 'Sprachen' }, { wert: 'IT', name: 'IT-Kenntnisse' },
  { wert: 'ZERTIFIKAT', name: 'Bescheinigungen / Zertifikate' }, { wert: 'AUSBILDUNG', name: 'Ausbildung / Abschluss' },
  { wert: 'PFLICHTFORTBILDUNG', name: 'Pflichtfortbildungen' },
];
export function kategorieName(wert: string): string { return KATEGORIEN.find(k => k.wert === wert)?.name ?? wert; }
export function heute(): string { return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Berlin' }).format(new Date()); }
export function fehlertext(e: unknown): string { return e instanceof ApiFehler ? [e.message, ...new Set(Object.values(e.felder))].join(' ') : 'Die Anfrage konnte nicht ausgeführt werden.'; }
@Injectable({ providedIn: 'root' })
export class EntwicklungApi {
  private readonly http = inject(HttpClient);
  laden<T>(bereich: string, params: Record<string, string | number> = {}): Promise<T> { return firstValueFrom(mitFehlern(this.http.get<T>(`/api/entwicklung/${bereich}`, { params }))); }
  speichern<T extends Datensatz>(bereich: string, entwurf: Entwurf<T>): Promise<T> {
    const { id, ...daten } = entwurf;
    return firstValueFrom(mitFehlern(id ? this.http.put<T>(`/api/entwicklung/${bereich}/${id}`, daten) : this.http.post<T>(`/api/entwicklung/${bereich}`, daten)));
  }
  loeschen(bereich: string, d: Datensatz): Promise<void> { return firstValueFrom(mitFehlern(this.http.delete<void>(`/api/entwicklung/${bereich}/${d.id}`, { params: { version: d.version } }))); }
}
