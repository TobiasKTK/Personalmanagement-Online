export type Kontaktart = 'INTERN' | 'GESCHAEFTLICH' | 'NOTFALL';
export type Bildungsart = 'SCHULE' | 'BERUF' | 'HOCHSCHULE' | 'SONSTIGES';
export type Bildungsstatus = 'GEPLANT' | 'LAUFEND' | 'ABGESCHLOSSEN' | 'ABGEBROCHEN';
export interface Zeitraum { beginn: string | null; ende: string | null; hinweis: string; }
export interface Kontakt { art: Kontaktart; name: string; beziehung: string; telefon: string; email: string; prioritaet: number; hinweis: string; }
export interface Vollmacht extends Zeitraum { id?: string; bezeichnung: string; umfang: string; erteiltDurch: string; }
export interface Kostentraeger extends Zeitraum { nummer: string; bezeichnung: string; }
export interface Beauftragung extends Zeitraum { id?: string; aufgabe: string; bereich: string; bestelltDurch: string; }
export interface Bildung extends Zeitraum { art: Bildungsart; einrichtung: string; fachrichtung: string; abschluss: string; status: Bildungsstatus; }
export interface Fortbildung extends Zeitraum { id?: string; titel: string; anbieter: string; dauerStunden: number | null; status: Bildungsstatus; nachweis: string; gueltigBis: string | null; }
export interface Fahrzeug extends Zeitraum { hersteller: string; modell: string; kennung: string; leasinganbieter: string; monatsrate: number | null; }
export interface Erweiterungen {
  version: number; kontakte: Kontakt[]; vollmachten: Vollmacht[]; kostentraeger: Kostentraeger[];
  beauftragungen: Beauftragung[]; bildung: Bildung[]; fortbildungen: Fortbildung[];
  dienstwagen: Fahrzeug[]; dienstraeder: Fahrzeug[];
}
export type Listenname = Exclude<keyof Erweiterungen, 'version'>;
export interface Fte { wochenstunden: number | null; vollzeitWochenstunden: number; wert: number | null; basisVersion: number; stichtag: string; wochenstundenBekannt: boolean; }
export interface ErweiterteAkte { daten: Erweiterungen; fte: Fte; }
