import { HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, throwError } from 'rxjs';

export class ApiFehler extends Error {
  constructor(meldung: string, readonly felder: Record<string, string> = {}) { super(meldung); }
}
export function mitFehlern<T>(anfrage: Observable<T>): Observable<T> {
  return anfrage.pipe(catchError((fehler: HttpErrorResponse) => {
    const details = fehler.error;
    const meldung = fehler.status === 0 ? 'Der Server ist nicht erreichbar. Bitte versuchen Sie es erneut.'
      : typeof details?.meldung === 'string' ? details.meldung : 'Die Anfrage konnte nicht ausgeführt werden.';
    return throwError(() => new ApiFehler(meldung, details?.felder ?? {}));
  }));
}
