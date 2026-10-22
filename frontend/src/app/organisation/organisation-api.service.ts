import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Organisation } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';

@Injectable({ providedIn: 'root' })
export class OrganisationApiService {
  private readonly http = inject(HttpClient);
  organisationen(): Observable<Organisation[]> { return mitFehlern(this.http.get<Organisation[]>('/api/organisationseinheiten')); }
  revisionen(seite: number): Observable<OrganisationRevision[]> { return mitFehlern(this.http.get<OrganisationRevision[]>('/api/organisationseinheiten/revisionen', { params: { seite } })); }
  stand(id: number): Observable<Organisation[]> { return mitFehlern(this.http.get<Organisation[]>(`/api/organisationseinheiten/revisionen/${id}`)); }
  organisationSpeichern(name: string, person?: Organisation, parentId: number | null = person?.parentId ?? null): Observable<Organisation> {
    return mitFehlern(person ? this.http.put<Organisation>(`/api/organisationseinheiten/${person.id}`, { name, version: person.version, parentId })
      : this.http.post<Organisation>('/api/organisationseinheiten', { name, parentId }));
  }
  organisationLoeschen(o: Organisation): Observable<void> {
    return mitFehlern(this.http.delete<void>(`/api/organisationseinheiten/${o.id}`, { params: { version: o.version } }));
  }

}
export interface OrganisationRevision { id: number; zeitpunkt: string; bearbeiter: string; beschreibung: string; }
