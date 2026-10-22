import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Sitzung } from '../shared/model';
import { mitFehlern } from '../shared/api-fehler';
import { MandantenKontextService } from '../mandant/mandanten-kontext.service';

@Injectable({ providedIn: 'root' })
export class SitzungService {
  private readonly http = inject(HttpClient);
  private readonly mandant = inject(MandantenKontextService);
  readonly sitzung = signal<Sitzung | null>(null);
  private anfrage?: Promise<Sitzung>;
  laden(): Promise<Sitzung> {
    return this.anfrage ??= firstValueFrom(mitFehlern(this.http.get<Sitzung>('/api/sitzung')))
      .then(s => { this.sitzung.set(s); this.mandant.id.set(s.mandantenspeicherId); return s; })
      .catch(e => { this.anfrage = undefined; throw e; });
  }
  async abmelden(): Promise<void> {
    const antwort = await firstValueFrom(mitFehlern(this.http.post<{ weiterleitung: string } | null>('/logout', null)));
    this.sitzung.set(null); this.mandant.id.set(null); this.anfrage = undefined;
    location.assign(antwort?.weiterleitung ?? '/login');
  }
}
