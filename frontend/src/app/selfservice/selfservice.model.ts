import { Personaldaten, Personaldokument } from '../personaldaten/personaldaten.model';
export interface Kontakt { telefon: string; email: string; }
export interface Profil { mitarbeiterId: number; name: string; kontakt: Kontakt; kontaktVersion: number; bank: Personaldaten['bank']; bankVersion: number; }
export interface Antrag { id: number; mitarbeiterId: number; name: string; antragsteller: string; art: 'KONTAKT' | 'BANK'; vorher: Kontakt | Personaldaten['bank']; nachher: Kontakt | Personaldaten['bank']; bestandsversion: number; status: 'BEANTRAGT' | 'GENEHMIGT' | 'ABGELEHNT'; grund: string; erstellt: string; entschieden: string | null; bearbeiter: string | null; version: number; entscheidbar: boolean; }
export interface Seite<T> { eintraege: T[]; gesamt: number; }
export type Dokument = Personaldokument;
