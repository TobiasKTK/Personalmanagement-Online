export interface Seite<T> { eintraege: T[]; gesamt: number; seite: number; groesse: number; }
export interface Organisationswert {
  organisationId: number | null; organisation: string; historieBekannt: boolean;
  beschaeftigte: number; wochenstunden: number | null; fte: number | null;
}
export interface OffeneStelle {
  id: number; bezeichnung: string; organisationId: number; organisation: string; profil: string; anteil: number;
}
export interface Qualifikationsluecke {
  mitarbeiterId: number; personalnummer: number; name: string; organisationId: number | null;
  organisation: string; historieBekannt: boolean; kenntnisId: number; kenntnis: string;
  kategorie: string; soll: number; ist: number; differenz: number; grund: string;
}
export interface Auswertung {
  stichtag: string; organisationseinheitId: number | null; vollzeitWochenstunden: number;
  beschaeftigte: number; wochenstunden: number; fte: number; historieUnbekanntFirma: number;
  organisationen: Seite<Organisationswert>; offeneStellen: Seite<OffeneStelle>;
  qualifikationsluecken: Seite<Qualifikationsluecke>; hinweise: string[];
}
