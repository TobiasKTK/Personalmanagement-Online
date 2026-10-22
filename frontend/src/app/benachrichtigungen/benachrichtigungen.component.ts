import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { mitFehlern } from '../shared/api-fehler';
import { BenachrichtigungenService } from './benachrichtigungen.service';
interface Nachricht { id: number; antragId: number | null; text: string; erstellt: string; gelesen: boolean; ziel: string | null; }
@Component({ selector: 'app-benachrichtigungen', standalone: true, imports: [FormsModule, DatePipe], template: `
  <section class="team-panel administration"><h1>Benachrichtigungen</h1>
    <p>Deine Erinnerungen, Aufgaben und Entscheidungen zu Anträgen.</p>
    @if (fehler()) { <p role="alert" class="banner error">{{ fehler() }}</p> }
    <label><input type="checkbox" [(ngModel)]="nurUngelesen" [disabled]="laedt()" (change)="seite = 0; laden()"> Nur ungelesene Nachrichten</label>
    <button class="button secondary" [disabled]="laedt()" (click)="laden()">Aktualisieren</button>
    <ul class="management-list">@for (n of eintraege(); track n.id) {
      <li><div><strong>{{ n.text }}</strong><p>{{ n.erstellt | date:'dd.MM.yyyy HH:mm' }} · {{ n.gelesen ? 'Gelesen' : 'Ungelesen' }}</p></div>
        <div class="actions"><button class="button primary compact" [disabled]="laedt()" (click)="oeffnen(n)">{{ n.ziel ? 'Datensatz öffnen' : 'Antrag öffnen' }}</button>
          @if (!n.gelesen) { <button class="button secondary compact" [disabled]="laedt()" (click)="lesen(n)">Als gelesen markieren</button> }</div></li>
    } @empty { <li>{{ laedt() ? 'Nachrichten werden geladen …' : 'Keine Benachrichtigungen für diese Auswahl.' }}</li> }</ul>
    <div class="pagination"><span>{{ gesamt() }} Nachrichten · Seite {{ seite + 1 }}</span><div>
      <button class="page-button" aria-label="Vorherige Nachrichtenseite" [disabled]="seite === 0 || laedt()" (click)="seite = seite - 1; laden()">←</button>
      <button class="page-button" aria-label="Nächste Nachrichtenseite" [disabled]="(seite + 1) * 20 >= gesamt() || laedt()" (click)="seite = seite + 1; laden()">→</button>
    </div></div>
  </section>` })
export class BenachrichtigungenComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly nachrichten = inject(BenachrichtigungenService);
  readonly eintraege = signal<Nachricht[]>([]);
  readonly gesamt = signal(0);
  readonly fehler = signal('');
  readonly laedt = signal(false);
  nurUngelesen = false;
  seite = 0;
  ngOnInit(): void { void this.laden(); }
  async laden(): Promise<void> {
    this.laedt.set(true); this.fehler.set('');
    try {
      const d = await firstValueFrom(mitFehlern(this.http.get<{ eintraege: Nachricht[]; gesamt: number; ungelesen: number }>('/api/benachrichtigungen', { params: { ungelesen: this.nurUngelesen, seite: this.seite } })));
      if (!d.eintraege.length && this.seite > 0) { this.seite--; await this.laden(); return; }
      this.eintraege.set(d.eintraege); this.gesamt.set(d.gesamt); this.nachrichten.ungelesen.set(d.ungelesen);
    } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Nachrichten konnten nicht geladen werden.'); }
    finally { this.laedt.set(false); }
  }
  async lesen(n: Nachricht): Promise<void> { await this.markieren(n, false); }
  async oeffnen(n: Nachricht): Promise<void> { await this.markieren(n, true); }
  private async markieren(n: Nachricht, oeffnen: boolean): Promise<void> {
    this.laedt.set(true); this.fehler.set('');
    try {
      await firstValueFrom(mitFehlern(this.http.put('/api/benachrichtigungen/' + n.id + '/gelesen', null)));
      await this.nachrichten.aktualisieren();
      if (oeffnen) {
        if (n.ziel && /^\/(fristen|personalprozesse|self-service|mitarbeiter|kenntnisse)([/?#]|$)/.test(n.ziel)) await this.router.navigateByUrl(n.ziel);
        else if (n.antragId) await this.router.navigate(['/urlaub'], { queryParams: { antrag: n.antragId } });
      } else await this.laden();
    } catch (e) { this.fehler.set(e instanceof Error ? e.message : 'Nachricht konnte nicht geöffnet werden.'); }
    finally { this.laedt.set(false); }
  }
}
