import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Abwesenheit, EintragSeite } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';

@Injectable({ providedIn: 'root' })
export class AbwesenheitApiService {
  private readonly http = inject(HttpClient);
  abwesenheiten(id: number, seite: number): Observable<EintragSeite<Abwesenheit>> {
    return mitFehlern(this.http.get<EintragSeite<Abwesenheit>>(`/api/mitarbeiter/${id}/abwesenheiten`, { params: { seite, groesse: 20 } }));
  }
  abwesenheitSpeichern(person: number, eingabe: { art: string; beginn: string; ende: string; version?: number }, id?: number): Observable<Abwesenheit> {
    const url = `/api/mitarbeiter/${person}/abwesenheiten`;
    return mitFehlern(id ? this.http.put<Abwesenheit>(`${url}/${id}`, eingabe) : this.http.post<Abwesenheit>(url, eingabe));
  }
  abwesenheitLoeschen(person: number, a: Abwesenheit): Observable<void> {
    return mitFehlern(this.http.delete<void>(`/api/mitarbeiter/${person}/abwesenheiten/${a.id}`, { params: { version: a.version } }));
  }
  abwesenheitTagLoeschen(person: number, a: Abwesenheit, tag: string): Observable<void> {
    return mitFehlern(this.http.delete<void>(`/api/mitarbeiter/${person}/abwesenheiten/${a.id}/tage/${tag}`, { params: { version: a.version } }));
  }

}
