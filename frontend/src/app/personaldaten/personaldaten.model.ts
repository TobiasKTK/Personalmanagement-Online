import { Mitarbeiter } from '../shared/model';
export interface Kostenstelle { nummer: string; bezeichnung: string; anteil: number; }
export interface Vertretung { personId: number; aufgabe: string; beginn: string; ende: string | null; }
export interface Verguetung { gueltigAb: string; lohnart: string; bezeichnung: string; betrag: number; waehrung: string; basis: string; }
export interface Personaldaten {
  version: number;
  bank: { kontoinhaber: string; iban: string; bic: string; institut: string };
  profilId: number | null; disziplinarisch: boolean; fachlich: boolean;
  kostenstellen: Kostenstelle[]; vertretungen: Vertretung[];
  schwerbehinderung: { grad: number | null; gleichgestellt: boolean; merkzeichen: string; ausweisnummer: string; gueltigAb: string | null; gueltigBis: string | null };
  austrittsgrund: string; kuendigungsdatum: string | null; verguetungen: Verguetung[];
}
export interface Beschaeftigungszeit { id: number; beginn: string; ende: string; austrittsgrund: string; kuendigungsdatum: string | null; }
export interface Personalakte { person: Mitarbeiter; daten: Personaldaten; fruehereZeiten: Beschaeftigungszeit[]; betriebszugehoerigkeitTage: number; stichtag: string; profilName: string; }
export interface Revision { id: number; zeitpunkt: string; bearbeiter: string; daten: Personaldaten; person: Mitarbeiter; fruehereZeiten: Beschaeftigungszeit[]; profilName: string; }
export interface Personaldokument { id: number; titel: string; art: string; quelle: string; monat: string; dateiname: string; groesse: number; erstelltAm: string; }
