import { Component, DestroyRef, ElementRef, OnInit, inject, signal, viewChild } from '@angular/core';
import { BenachrichtigungenService } from './benachrichtigungen/benachrichtigungen.service';
import { NavigationEnd, NavigationSkipped, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SitzungService } from './sitzung/sitzung.service';
@Component({ selector: 'app-root', standalone: true, imports: [RouterLink, RouterLinkActive, RouterOutlet], templateUrl: './app.component.html' })
export class AppComponent implements OnInit {
  private readonly session = inject(SitzungService);
  readonly sitzung = this.session.sitzung;
  readonly nachrichten = inject(BenachrichtigungenService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  readonly ladefehler = signal('');
  readonly navigationOffen = signal(false);
  private readonly navigationToggle = viewChild<ElementRef<HTMLButtonElement>>('navigationToggle');
  private readonly inhalt = viewChild<ElementRef<HTMLElement>>('inhalt');
  readonly heute = new Intl.DateTimeFormat('de-DE', { dateStyle: 'long', timeZone: 'Europe/Berlin' }).format(new Date());
  async ngOnInit(): Promise<void> {
    // Navigation sofort anbinden; ein langsamer Nachrichtendienst darf das Menü nicht blockieren.
    // Nur bei Nutzung aktualisieren: Hintergrundabfragen würden das Inaktivitätslimit verlängern.
    const aktualisieren = () => { if (this.sitzung()?.mandantenspeicherId != null) void this.nachrichten.aktualisieren(); };
    const navigation = this.router.events.subscribe(e => {
      if (e instanceof NavigationEnd || e instanceof NavigationSkipped) {
        if (this.navigationOffen()) {
          this.navigationSchliessen();
          this.inhalt()?.nativeElement.focus();
        }
        aktualisieren();
      }
    });
    window.addEventListener('focus', aktualisieren);
    this.destroyRef.onDestroy(() => { navigation.unsubscribe(); window.removeEventListener('focus', aktualisieren); });
    try {
      await this.session.laden();
      if (this.destroyRef.destroyed) return;
      aktualisieren();
    } catch (e) { this.ladefehler.set(e instanceof Error ? e.message : 'Anmeldung konnte nicht geladen werden.'); }
  }
  zumInhaltSpringen(event: Event): void {
    // Ein bloßes Fragment würde wegen <base href="/"> die aktuelle Fachansicht verlassen.
    event.preventDefault();
    this.navigationSchliessen();
    const inhalt = this.inhalt()?.nativeElement;
    inhalt?.focus();
    inhalt?.scrollIntoView({ block: 'start' });
  }
  async abmelden(): Promise<void> {
    try { await this.session.abmelden(); } catch (e) { this.ladefehler.set(e instanceof Error ? e.message : 'Abmelden fehlgeschlagen.'); }
  }
  navigationSchliessen(fokusZurueck = false): void {
    if (!this.navigationOffen()) return;
    this.navigationOffen.set(false);
    if (fokusZurueck) this.navigationToggle()?.nativeElement.focus();
  }
}
