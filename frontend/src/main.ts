import { provideRouter, withRouterConfig } from '@angular/router';
import { routes } from './app/app.routes';
import { bootstrapApplication } from '@angular/platform-browser';
import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { AppComponent } from './app/app.component';
import { inject } from '@angular/core';
import { MandantenKontextService } from './app/mandant/mandanten-kontext.service';

bootstrapApplication(AppComponent, { providers: [
  // Abgebrochenes Browser-Zurück soll die ursprüngliche Position im Verlauf wiederherstellen.
  provideRouter(routes, withRouterConfig({ canceledNavigationResolution: 'computed' })),
  provideHttpClient(withInterceptors([
  (request, next) => {
    const id = inject(MandantenKontextService).id();
    return next(id !== null && request.url.startsWith('/api/') ? request.clone({ setHeaders: { 'X-Mandantenspeicher-ID': String(id) } }) : request);
  },
  (request, next) => next(request).pipe(catchError((error: HttpErrorResponse) => {
    if (error.status === 401) location.assign('/login');
    return throwError(() => error);
  })),
]))] })
  .catch(error => console.error('Personalmanagement Online konnte nicht gestartet werden.', error));
