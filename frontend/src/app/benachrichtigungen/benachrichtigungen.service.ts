import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
@Injectable({ providedIn: 'root' })
export class BenachrichtigungenService {
  private readonly http = inject(HttpClient);
  readonly ungelesen = signal(0);
  async aktualisieren(): Promise<void> {
    try { this.ungelesen.set((await firstValueFrom(this.http.get<{ ungelesen: number }>('/api/benachrichtigungen/anzahl'))).ungelesen); }
    catch { /* Bei einer vorübergehenden Verbindungsstörung den letzten Zähler behalten. */ }
  }
}
