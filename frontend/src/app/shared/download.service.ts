import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
@Injectable({ providedIn: 'root' })
export class DownloadService {
  private readonly http = inject(HttpClient);
  async laden(url: string, dateiname: string, params: Record<string, string | number | boolean> = {}): Promise<void> {
    let blob: Blob;
    try { blob = await firstValueFrom(this.http.get(url, { params, responseType: 'blob' })); }
    catch (e) {
      if (e instanceof HttpErrorResponse && e.error instanceof Blob) {
        let meldung = '';
        try { meldung = JSON.parse(await e.error.text()).meldung || ''; } catch { /* Kein JSON-Fehlertext vorhanden. */ }
        throw new Error(meldung || 'Export fehlgeschlagen. Bitte Anmeldung und Filter prüfen.');
      }
      throw e;
    }
    const href = URL.createObjectURL(blob), link = document.createElement('a');
    link.href = href; link.download = dateiname; document.body.appendChild(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(href), 1000);
  }
}
