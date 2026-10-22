import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router, Routes } from '@angular/router';
import { SitzungService } from './sitzung/sitzung.service';
import type { TeamComponent } from './mitarbeiter/team.component';

const angemeldet: CanActivateFn = async (_route, state) => {
  const sitzung = inject(SitzungService), router = inject(Router);
  try {
    const s = await sitzung.laden();
    return s.mandantenspeicherId !== null || state.url === '/mandanten' || state.url === '/administration/mandanten' ? true : router.parseUrl('/mandanten');
  } catch { return false; }
};
const systemverwaltung: CanActivateFn = () => {
  const sitzung = inject(SitzungService), router = inject(Router);
  return sitzung.laden().then(s => s.systemverwaltung || router.parseUrl('/mandanten')).catch(() => false);
};
const administration: CanActivateFn = () => {
  const session = inject(SitzungService), router = inject(Router);
  return session.laden().then(s => s.rolle === 'ADMIN' || router.parseUrl('/mitarbeiter')).catch(() => false);
};
const teamVerlassen: CanDeactivateFn<TeamComponent> = team => team.verlassenErlauben();
const personal: CanActivateFn = () => { const session = inject(SitzungService), router = inject(Router); return session.laden().then(s => s.rolle === 'PERSONAL' || s.rolle === 'ADMIN' || router.parseUrl('/mandanten')).catch(() => false); };
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'mandanten', canActivate: [angemeldet], loadComponent: () => import('./mandant/mandanten.component').then(m => m.MandantenComponent) },
  { path: 'administration/mandanten', canActivate: [systemverwaltung], loadComponent: () => import('./mandant/mandantenverwaltung.component').then(m => m.MandantenverwaltungComponent) },
  { path: 'dashboard', canActivate: [angemeldet], loadComponent: () => import('./dashboard/dashboard.component').then(m => m.DashboardComponent) },
  { path: 'exporte', canActivate: [personal], loadComponent: () => import('./planung/export.component').then(m => m.ExportComponent) },
  { path: 'organisation', canActivate: [angemeldet], loadComponent: () => import('./organisation/organisation.component').then(m => m.OrganisationComponent) },
  { path: 'stellen', canActivate: [personal], loadComponent: () => import('./entwicklung/stellen.component').then(m => m.StellenComponent) },
  { path: 'kenntnisse', canActivate: [personal], loadComponent: () => import('./entwicklung/kenntnisse.component').then(m => m.KenntnisseComponent) },
  { path: 'ausbildung', canActivate: [personal], loadComponent: () => import('./entwicklung/ausbildung.component').then(m => m.AusbildungComponent) },
  { path: 'kalender', canActivate: [angemeldet], loadComponent: () => import('./kalender/kalender.component').then(m => m.KalenderComponent) },
  { path: 'urlaub', canActivate: [angemeldet], loadComponent: () => import('./urlaub/urlaub.component').then(m => m.UrlaubComponent) },
  { path: 'benachrichtigungen', canActivate: [angemeldet], loadComponent: () => import('./benachrichtigungen/benachrichtigungen.component').then(m => m.BenachrichtigungenComponent) },
  { path: 'mitarbeiter', canActivate: [angemeldet], canDeactivate: [teamVerlassen], loadComponent: () => import('./mitarbeiter/team.component').then(m => m.TeamComponent) },
  { path: 'administration/organisationen', canActivate: [administration], loadComponent: () => import('./organisation/organisation.component').then(m => m.OrganisationComponent) },
  { path: 'administration/konten', canActivate: [administration], loadComponent: () => import('./konten/konten.component').then(m => m.KontenComponent) },
  { path: 'administration/planung', canActivate: [administration], loadComponent: () => import('./planung/planung.component').then(m => m.PlanungComponent) },
  { path: '**', redirectTo: 'mitarbeiter' },
];
