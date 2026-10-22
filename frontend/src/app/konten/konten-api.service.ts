import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Konto } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';

@Injectable({ providedIn: 'root' })
export class KontenApiService {
  private readonly http = inject(HttpClient);
  konten(): Observable<Konto[]> { return mitFehlern(this.http.get<Konto[]>('/api/konten')); }
  kontoSpeichern(konto: Konto & { passwort: string }, neu: boolean): Observable<void> {
    return mitFehlern(neu ? this.http.post<void>('/api/konten', konto) : this.http.put<void>('/api/konten', konto));
  }

}
