export interface Stellenzuordnung { profilId: number | null; version: number; }
export interface StellenzuordnungStand extends Stellenzuordnung { freitext: string; }

export interface MitarbeiterEingabe {
  personalnummer: number | null;
  titel: string;
  nachname: string;
  vorname: string;
  geburtsname: string;
  kuerzel: string;
  geburtsdatum: string | null;
  eintrittsdatum: string;
  austrittsdatum: string | null;
  organisationseinheit: string;
  organisationseinheitId?: number | null;
  stelle: string;
  stellenzuordnung?: Stellenzuordnung;
  beschaeftigungsart: string;
  wochenstunden: number;
  version?: number;
  datenGueltigAb?: string | null;
}

export interface Mitarbeiter extends MitarbeiterEingabe {
  altBeschaeftigungsstatus: string;
  altAbwesenheitsstatus: string;
  beschaeftigung: string;
  abwesenheitAmStichtag: string;
  stichtag: string;
  historieBekannt: boolean;
  datenGueltigAb: string | null;
  id: number;
  name: string;
  personalnummer: number;
  version: number;
}

export interface MitarbeiterSeite {
  mitarbeiter: Mitarbeiter[];
  gesamt: number;
  seite: number;
  groesse: number;
}

export interface Statistik {
  historieUnbekannt: number;
  anzahl: number;
  wochenstunden: number;
  bevorstehendeEintritte: number;
  aktiv: number;
  ausgeschieden: number;
  stichtag: string;
}

export interface Organisation { id: number; name: string; version: number; parentId?: number | null; }
export interface Sitzung { name: string; rolle: 'LESEN' | 'PERSONAL' | 'ADMIN' | 'MITARBEITER' | 'TEAMLEITUNG' | null; mandantenspeicherId: number | null; firma: string; systemverwaltung: boolean; keycloak: boolean; }
export interface Konto { name: string; rolle: NonNullable<Sitzung['rolle']>; aktiv: boolean; }
export interface Abwesenheit { id: number; mitarbeiterId: number; art: string; beginn: string; ende: string; version: number; halberTag: boolean; }
export interface Verlauf { id: number; zeitpunkt: string; bearbeiter: string; aktion: string; vorher: string | null; nachher: string | null; }
export interface EintragSeite<T> { eintraege: T[]; gesamt: number; }
export type Filter = Record<string, string | number>;

export function heutigesDatum(): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}

export const BESCHAEFTIGUNGSART = [
  'Aushilfskräfte', 'Auszubildende',
  'befristete Mitarbeiter', 'Extern', 'Festangestellte',
  'Praktikanten Diplomanden Doktoranden', 'Sonstige', 'Versorgungsempfänger',
  'Werkstudenten', 'Zeitarbeitskräfte',
];

