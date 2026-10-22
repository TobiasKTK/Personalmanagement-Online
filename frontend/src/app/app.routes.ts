import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router, Routes } from '@angular/router';
import { SitzungService } from './sitzung/sitzung.service';
import type { TeamComponent } from './mitarbeiter/team.component';
import type { DatenimportComponent } from './datenimport/datenimport.component';

const angemeldet: CanActivateFn = async (_route, state) => {
  const sitzung = inject(SitzungService), router = inject(Router);
  try {
    const s = await sitzung.laden();
    return s.mandantenspeicherId !== null || state.url === '/mandanten' ? true : router.parseUrl('/mandanten');
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
const kontenverwaltung: CanActivateFn = () => {
  const session = inject(SitzungService), router = inject(Router);
  return session.laden().then(s => s.systemverwaltung || (s.mandantenspeicherId !== null && s.rolle === 'ADMIN') || router.parseUrl('/mitarbeiter')).catch(() => false);
};
const teamVerlassen: CanDeactivateFn<TeamComponent> = team => team.verlassenErlauben();
const importVerlassen: CanDeactivateFn<DatenimportComponent> = component => component.verlassenErlauben();
const personal: CanActivateFn = () => { const session = inject(SitzungService), router = inject(Router); return session.laden().then(s => s.rolle === 'PERSONAL' || s.rolle === 'ADMIN' || router.parseUrl('/mandanten')).catch(() => false); };
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'mandanten', loadComponent: () => import('./mandant/firmen.component').then(m => m.FirmenComponent), children: [
    { path: '', pathMatch: 'full', canActivate: [angemeldet], loadComponent: () => import('./mandant/mandanten.component').then(m => m.MandantenComponent) },
    { path: 'verwaltung', canActivate: [systemverwaltung], loadComponent: () => import('./mandant/mandantenverwaltung.component').then(m => m.MandantenverwaltungComponent) },
  ] },
  { path: 'administration/mandanten', pathMatch: 'full', redirectTo: 'mandanten/verwaltung' },
  { path: 'dashboard', canActivate: [angemeldet], loadComponent: () => import('./dashboard/dashboard.component').then(m => m.DashboardComponent) },
  { path: 'exporte', canActivate: [personal], loadComponent: () => import('./planung/export.component').then(m => m.ExportComponent) },
  { path: 'auswertungen', canActivate: [personal], loadComponent: () => import('./auswertungen/auswertungen.component').then(m => m.AuswertungenComponent) },
  { path: 'import', canActivate: [personal], canDeactivate: [importVerlassen], loadComponent: () => import('./datenimport/datenimport.component').then(m => m.DatenimportComponent) },
  { path: 'fortbildungen', canActivate: [personal], loadComponent: () => import('./fortbildungen/fortbildungen.component').then(m => m.FortbildungenComponent) },
  { path: 'organisation', canActivate: [angemeldet], loadComponent: () => import('./organisation/organisation.component').then(m => m.OrganisationComponent) },
  { path: 'stellen', canActivate: [personal], loadComponent: () => import('./entwicklung/stellen.component').then(m => m.StellenComponent) },
  { path: 'kenntnisse', canActivate: [personal], loadComponent: () => import('./entwicklung/kenntnisse.component').then(m => m.KenntnisseComponent) },
  { path: 'ausbildung', canActivate: [personal], loadComponent: () => import('./entwicklung/ausbildung.component').then(m => m.AusbildungComponent) },
  { path: 'kalender', canActivate: [angemeldet], loadComponent: () => import('./kalender/kalender.component').then(m => m.KalenderComponent) },
  { path: 'urlaub', canActivate: [angemeldet], loadComponent: () => import('./urlaub/urlaub.component').then(m => m.UrlaubComponent) },
  { path: 'benachrichtigungen', canActivate: [angemeldet], loadComponent: () => import('./benachrichtigungen/benachrichtigungen.component').then(m => m.BenachrichtigungenComponent) },
  { path: 'fristen', canActivate: [personal], loadComponent: () => import('./fristen/fristen.component').then(m => m.FristenComponent) },
  { path: 'personalprozesse', canActivate: [angemeldet], loadComponent: () => import('./personalprozesse/personalprozesse.component').then(m => m.PersonalprozesseComponent) },
  { path: 'self-service/pruefung', canActivate: [personal], data: { pruefung: true }, loadComponent: () => import('./selfservice/selfservice.component').then(m => m.SelfServiceComponent) },
  { path: 'self-service', canActivate: [angemeldet], loadComponent: () => import('./selfservice/selfservice.component').then(m => m.SelfServiceComponent) },
  { path: 'mitarbeiter', canActivate: [angemeldet], canDeactivate: [teamVerlassen], loadComponent: () => import('./mitarbeiter/team.component').then(m => m.TeamComponent) },
  { path: 'administration/organisationen', canActivate: [administration], loadComponent: () => import('./organisation/organisation.component').then(m => m.OrganisationComponent) },
  { path: 'administration/konten', canActivate: [kontenverwaltung], loadComponent: () => import('./konten/konten.component').then(m => m.KontenComponent) },
  { path: 'administration/planung', canActivate: [administration], loadComponent: () => import('./planung/planung.component').then(m => m.PlanungComponent) },
  { path: '**', redirectTo: 'mitarbeiter' },
];
