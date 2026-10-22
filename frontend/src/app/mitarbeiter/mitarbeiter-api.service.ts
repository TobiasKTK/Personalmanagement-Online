import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Mitarbeiter, MitarbeiterEingabe, MitarbeiterSeite, Statistik, Filter } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';

@Injectable({ providedIn: 'root' })
export class MitarbeiterApiService {
  private readonly http = inject(HttpClient);
  finden(id: number): Observable<Mitarbeiter> { return mitFehlern(this.http.get<Mitarbeiter>(`/api/mitarbeiter/${id}`)); }
  liste(suche: string, seite: number, groesse: number, filter: Filter = {}): Observable<MitarbeiterSeite> {
    return mitFehlern(this.http.get<MitarbeiterSeite>('/api/mitarbeiter', {
      params: { suche, seite, groesse, ...filter },
    }));
  }

  statistik(filter: Filter = {}): Observable<Statistik> {
    return mitFehlern(this.http.get<Statistik>('/api/statistik', { params: filter }));
  }

  naechstePersonalnummer(): Observable<{ personalnummer: number }> {
    return mitFehlern(this.http.get<{ personalnummer: number }>('/api/mitarbeiter/naechste-personalnummer'));
  }

  speichern(eingabe: MitarbeiterEingabe, id?: number): Observable<Mitarbeiter> {
    return mitFehlern(id === undefined
      ? this.http.post<Mitarbeiter>('/api/mitarbeiter', eingabe)
      : this.http.put<Mitarbeiter>(`/api/mitarbeiter/${id}`, eingabe));
  }

  loeschen(person: Mitarbeiter): Observable<void> {
    return mitFehlern(this.http.delete<void>(`/api/mitarbeiter/${person.id}`, {
      params: { version: person.version },
    }));
  }


}
