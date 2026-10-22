import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { heutigesDatum } from '../shared/model';
import { ApiFehler, mitFehlern } from '../shared/api-fehler';
interface Person { id: number; name: string; organisation: string; abwesenheiten: { beginn: string; ende: string; halberTag: boolean }[]; kenntnisse: { name: string; niveau: number; gueltigBis: string | null }[]; }
interface Seite { eintraege: Person[]; gesamt: number; }
@Component({ selector: 'app-mein-team', standalone: true, imports: [FormsModule, DatePipe, RouterLink], template: `
  <section class="team-panel" aria-labelledby="mein-team-titel" [attr.aria-busy]="arbeitet()">
    <div class="panel-heading"><div><p class="eyebrow">TEAMLEITUNG</p><h1 id="mein-team-titel">Mein Team</h1><p>Deine zugeordneten Organisationseinheiten mit Abwesenheiten und Kenntnissen.</p></div>
      <a class="button primary" routerLink="/urlaub">Urlaubsfreigaben öffnen</a></div>
    <form (ngSubmit)="laden(0)"><fieldset class="filter-grid" [disabled]="arbeitet()"><legend class="sr-only">Team filtern</legend>
      <label class="field">Name suchen<input name="suche" [(ngModel)]="suche" maxlength="120" type="search"></label>
      <label class="field">Abwesenheiten von<input name="von" type="date" [(ngModel)]="von" required></label>
      <label class="field">Abwesenheiten bis<input name="bis" type="date" [(ngModel)]="bis" [min]="von" required></label>
      <button class="button secondary">Aktualisieren</button>
    </fieldset></form>
    @if (fehler()) { <p class="banner error" role="alert">{{ fehler() }}</p> }
    @if (arbeitet()) { <p role="status">Team wird geladen …</p> }
    @if (daten(); as d) {
      <p role="status">{{ d.gesamt }} Mitarbeitende · Seite {{ seite + 1 }}</p>
      <ul class="team-liste" aria-label="Mitarbeitende im eigenen Team">
        @for (p of d.eintraege; track p.id) {
          <li><div><h2>{{ p.name }}</h2><p>{{ p.organisation }}</p>
            <h3>Abwesenheiten im Zeitraum</h3>
            <ul>@for (a of p.abwesenheiten; track $index) { <li>{{ a.beginn | date:'dd.MM.yyyy' }} – {{ a.ende | date:'dd.MM.yyyy' }}{{ a.halberTag ? ' · Halber Tag' : '' }}</li> } @empty { <li>Keine Abwesenheit erfasst.</li> }</ul>
            <h3>Kenntnisse</h3><ul>@for (k of p.kenntnisse; track $index) { <li>{{ k.name }} · Niveau {{ k.niveau }}@if (k.gueltigBis) { · gültig bis {{ k.gueltigBis | date:'dd.MM.yyyy' }} }</li> } @empty { <li>Keine Kenntnisse hinterlegt.</li> }</ul>
          </div></li>
        } @empty { <li>Keine Mitarbeitenden gefunden. Die Administration verwaltet deine Teamzuständigkeiten unter „Benutzer &amp; Zugänge“.</li> }
      </ul>
      <nav class="pagination" aria-label="Teamseiten"><button class="button secondary" [disabled]="arbeitet() || seite === 0" (click)="laden(seite - 1)">Zurück</button><button class="button secondary" [disabled]="arbeitet() || (seite + 1) * 20 >= d.gesamt" (click)="laden(seite + 1)">Weiter</button></nav>
    }
  </section>`, styles: `
    fieldset.filter-grid { border: 0; margin: 0; min-width: 0; background: var(--surface-subtle); }
    .team-panel > p:not(.banner) { padding-inline: var(--space-5); }
    .team-liste { list-style: none; margin: 0; padding: 0 var(--space-5); overflow-wrap: anywhere; }
    .team-liste > li { padding-block: var(--space-5); border-top: 1px solid var(--line); }
    .team-liste p { color: var(--muted); margin-block: var(--space-2); }
    .team-liste ul { padding-left: var(--space-5); }
    .team-liste ul > li + li { margin-top: var(--space-2); }
    @media (max-width: 780px) {
      .team-liste, .team-panel > p:not(.banner) { padding-inline: var(--space-4); }
    }
  ` })
export class MeinTeamComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly daten = signal<Seite | null>(null);
  readonly arbeitet = signal(false);
  readonly fehler = signal('');
  suche = ''; von = heutigesDatum(); bis = heutigesDatum(); seite = 0;
  ngOnInit(): void { void this.laden(0); }
  async laden(seite: number): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.daten.set(null);
    try {
      const d = await firstValueFrom(mitFehlern(this.http.get<Seite>('/api/team', { params: { seite, suche: this.suche, von: this.von, bis: this.bis } })));
      this.seite = seite; this.daten.set(d);
    } catch (e) { this.fehler.set(e instanceof ApiFehler ? e.message : 'Team konnte nicht geladen werden.'); }
    finally { this.arbeitet.set(false); }
  }
}
