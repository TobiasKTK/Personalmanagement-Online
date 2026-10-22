import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { Urlaubskonto } from '../urlaub/urlaubskonto.component';
interface Termin { id: number; name: string; datum: string; }
interface Dashboard { heute: string; abwesendGesamt: number; abwesend: { id: number; name: string; art: string; halberTag: boolean }[]; eintritte: Termin[]; austritte: Termin[]; offeneFreigaben: number | null; eigeneOffene: number; urlaubskonto: Urlaubskonto | null; }
@Component({ selector: 'app-dashboard', standalone: true, imports: [DatePipe, RouterLink], templateUrl: './dashboard.component.html', styleUrl: './dashboard.component.css' })
export class DashboardComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly daten = signal<Dashboard | null>(null);
  readonly fehler = signal('');
  readonly laedt = signal(false);
  readonly arten: Record<string, string> = { URLAUB: 'Urlaub', KRANKHEIT: 'Krankheit', ELTERNZEIT: 'Elternzeit', SONSTIGES: 'Sonstiges' };
  ngOnInit(): void { void this.laden(); }
  async laden(): Promise<void> {
    this.laedt.set(true); this.fehler.set('');
    try { this.daten.set(await firstValueFrom(mitFehlern(this.http.get<Dashboard>('/api/dashboard')))); }
    catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Übersicht konnte nicht geladen werden.'); }
    finally { this.laedt.set(false); }
  }
}
