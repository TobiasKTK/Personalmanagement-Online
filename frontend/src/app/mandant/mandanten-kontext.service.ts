import { Injectable, signal } from '@angular/core';
@Injectable({ providedIn: 'root' })
export class MandantenKontextService {
  readonly id = signal<number | null>(null);
}
