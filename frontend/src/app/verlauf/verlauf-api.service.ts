import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Verlauf, EintragSeite } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';

@Injectable({ providedIn: 'root' })
export class VerlaufApiService {
  private readonly http = inject(HttpClient);
  verlauf(person: number, seite: number): Observable<EintragSeite<Verlauf>> {
    return mitFehlern(this.http.get<EintragSeite<Verlauf>>(`/api/mitarbeiter/${person}/verlauf`, { params: { seite, groesse: 20 } }));
  }


}
