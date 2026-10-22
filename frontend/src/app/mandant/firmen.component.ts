import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SitzungService } from '../sitzung/sitzung.service';

@Component({
  selector: 'app-firmen', standalone: true, imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <header class="page-heading"><h1>Firmen</h1></header>
    @if (sitzung()?.systemverwaltung) {
      <nav class="firmen-navigation" aria-label="Firmenbereiche">
        <a routerLink="/mandanten" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">Firma auswählen</a>
        <a routerLink="/mandanten/verwaltung" routerLinkActive="active" ariaCurrentWhenActive="page">Verwaltung</a>
      </nav>
    }
    <router-outlet />
  `,
  styles: `
    .firmen-navigation { display: flex; flex-wrap: wrap; gap: var(--space-2); border-bottom: 1px solid var(--line); padding-bottom: var(--space-2); }
    .firmen-navigation a { display: inline-flex; align-items: center; min-height: 44px; padding: var(--space-2) var(--space-4); border-radius: var(--radius); color: var(--muted); font-weight: 600; text-decoration: none; }
    .firmen-navigation a:hover { background: var(--surface-subtle); color: var(--primary); }
    .firmen-navigation a.active { background: var(--primary-soft); color: var(--primary); box-shadow: inset 0 -3px var(--primary); }
  `,
})
export class FirmenComponent {
  readonly sitzung = inject(SitzungService).sitzung;
}
