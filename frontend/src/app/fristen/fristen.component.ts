import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { heutigesDatum } from '../shared/model';

interface Frist {
  id: number; mitarbeiterId: number; person: string; art: string; titel: string; ablauf: string; ziel: string;
  zustaendig: string | null; vorlaufTage: number; wiedervorlage: string | null; erledigt: boolean; version: number;
}
@Component({ selector: 'app-fristen', standalone: true, imports: [FormsModule, DatePipe],
  templateUrl: './fristen.component.html', styleUrl: './fristen.component.css' })
export class FristenComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly fristen = signal<Frist[]>([]);
  readonly zustaendige = signal<{ name: string }[]>([]);
  readonly arbeitet = signal(false); readonly fehler = signal(''); readonly meldung = signal('');
  readonly filter = signal('OFFEN'); readonly suche = signal('');
  readonly auswahl = signal<Frist | null>(null);
  readonly sichtbar = computed(() => this.fristen().filter(f =>
    (this.filter() === 'ALLE' || (this.filter() === 'ERLEDIGT' ? f.erledigt : !f.erledigt)) &&
    (this.filter() !== 'UEBERFAELLIG' || f.ablauf < heutigesDatum()) &&
    (f.person + ' ' + f.titel).toLocaleLowerCase('de').includes(this.suche().toLocaleLowerCase('de'))));
  ngOnInit(): void { void this.laden(); }
  async laden(): Promise<void> {
    await this.ausfuehren(async () => {
      await firstValueFrom(mitFehlern(this.http.post('/api/fristen/abgleich', null)));
      const [fristen, zustaendige] = await Promise.all([
        firstValueFrom(mitFehlern(this.http.get<Frist[]>('/api/fristen'))),
        firstValueFrom(mitFehlern(this.http.get<{ name: string }[]>('/api/fristen/zustaendige'))),
      ]);
      this.fristen.set(fristen); this.zustaendige.set(zustaendige); this.auswahl.set(null);
    });
  }
  bearbeiten(f: Frist): void { this.auswahl.set({ ...f }); this.meldung.set(''); this.fehler.set(''); }
  status(f: Frist): string {
    if (f.erledigt) return 'Erledigt';
    if (f.ablauf < heutigesDatum()) return 'Überfällig';
    if (f.wiedervorlage) return 'Wiedervorlage';
    return 'Offen';
  }
  art(f: Frist): string { return ({ QUALIFIKATION: 'Qualifikation', VOLLMACHT: 'Vollmacht', BEAUFTRAGUNG: 'Beauftragung', FORTBILDUNG: 'Fortbildung' } as Record<string, string>)[f.art] ?? f.art; }
  async speichern(): Promise<void> {
    const f = this.auswahl(); if (!f) return;
    await this.ausfuehren(async () => {
      const neu = await firstValueFrom(mitFehlern(this.http.put<Frist>('/api/fristen/' + f.id, {
        zustaendig: f.zustaendig || null, vorlaufTage: f.vorlaufTage, wiedervorlage: f.wiedervorlage || null,
        erledigt: f.erledigt, version: f.version,
      })));
      this.fristen.update(l => l.map(e => e.id === neu.id ? neu : e)); this.auswahl.set(null);
      this.meldung.set('Frist gespeichert.');
    });
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return; this.arbeitet.set(true); this.fehler.set('');
    try { await aktion(); } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Die Anfrage ist fehlgeschlagen.'); }
    finally { this.arbeitet.set(false); }
  }
}
