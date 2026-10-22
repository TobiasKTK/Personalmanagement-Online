export interface ImportDaten {
  personalnummer: number | null; vorname: string; nachname: string; eintrittsdatum: string | null;
  wochenstunden: number | null; organisationseinheit: string;
  titel: string; geburtsname: string; kuerzel: string; geburtsdatum: string | null;
  austrittsdatum: string | null; stelle: string; beschaeftigungsart: string;
}
export interface ImportZeile { nummer: number; daten: ImportDaten; fehler: string[]; }
export interface ImportVorschau { id: string | null; gueltigBis: string | null; zeilen: ImportZeile[]; fehleranzahl: number; }
export const importFelder = [
  { id: 'personalnummer', name: 'Personalnummer', pflicht: true },
  { id: 'vorname', name: 'Vorname', pflicht: true }, { id: 'nachname', name: 'Nachname', pflicht: true },
  { id: 'eintrittsdatum', name: 'Eintrittsdatum', pflicht: true }, { id: 'wochenstunden', name: 'Wochenstunden', pflicht: true },
  { id: 'organisationseinheit', name: 'Organisationseinheit', pflicht: false },
  { id: 'titel', name: 'Titel', pflicht: false }, { id: 'geburtsname', name: 'Geburtsname', pflicht: false },
  { id: 'kuerzel', name: 'Kürzel', pflicht: false }, { id: 'geburtsdatum', name: 'Geburtsdatum', pflicht: false },
  { id: 'austrittsdatum', name: 'Austrittsdatum', pflicht: false }, { id: 'stelle', name: 'Stelle', pflicht: false },
  { id: 'beschaeftigungsart', name: 'Beschäftigungsart', pflicht: false },
] as const;
