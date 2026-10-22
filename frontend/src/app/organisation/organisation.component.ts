import { Component, ElementRef, OnDestroy, OnInit, inject, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiFehler } from '../shared/api-fehler';
import { OrganisationApiService, OrganisationRevision } from './organisation-api.service';
import { SitzungService } from '../sitzung/sitzung.service';
import { Organisation } from '../shared/model';
import { OrganisationsDragScroll } from './organisations-drag-scroll';

@Component({
  selector: 'app-organisation', standalone: true, imports: [FormsModule, CommonModule],
  templateUrl: './organisation.component.html',
  styleUrl: './organisation.component.css',
})
export class OrganisationComponent implements OnInit, OnDestroy {
  private readonly api = inject(OrganisationApiService);
  private readonly sitzung = inject(SitzungService);
  readonly admin = signal(false);
  readonly revisionen = signal<OrganisationRevision[]>([]);
  ansicht = 'baum';
  revision: number | null = null;
  revisionsseite = 0;
  parentId: number | null = null;
  private gezogen?: Organisation;
  private readonly struktur = viewChild<ElementRef<HTMLElement>>('struktur');
  private readonly dragScroll = new OrganisationsDragScroll();
  readonly geaendert = output<void>();
  readonly organisationen = signal<Organisation[]>([]);
  readonly fehler = signal('');
  readonly meldung = signal('');
  readonly arbeitet = signal(false);
  readonly bereit = signal(false);
  organisation?: Organisation;
  orgName = '';

  ngOnInit(): void { void this.standWechseln(); }
  ngOnDestroy(): void { this.dragScroll.stoppen(); }
  async laden(): Promise<void> {
    const [organisationen, revisionen, sitzung] = await Promise.all([
      firstValueFrom(this.revision === null ? this.api.organisationen() : this.api.stand(this.revision)),
      firstValueFrom(this.api.revisionen(this.revisionsseite)), this.sitzung.laden(),
    ]);
    this.organisationen.set(organisationen); this.revisionen.set(revisionen); this.admin.set(sitzung.rolle === 'ADMIN'); this.bereit.set(true);
  }
  kinder(id: number | null): Organisation[] { return this.organisationen().filter(o => (o.parentId ?? null) === id); }
  parentName(o: Organisation): string { return this.organisationen().find(p => p.id === o.parentId)?.name ?? 'Oberste Ebene'; }
  bearbeitbar(): boolean { return this.admin() && this.revision === null && !this.arbeitet() && this.bereit(); }
  orgBearbeiten(o?: Organisation): void { this.organisation = o; this.orgName = o?.name ?? ''; this.parentId = o?.parentId ?? null; }
  async standWechseln(): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.bereit.set(false); this.orgBearbeiten(); this.fehler.set(''); this.meldung.set(''); this.organisationen.set([]);
    try { await this.laden(); } catch (e) { this.fehler.set(this.text(e)); } finally { this.arbeitet.set(false); }
  }
  async revisionenBlaettern(delta: number): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true);
    this.revisionsseite += delta;
    try { this.revisionen.set(await firstValueFrom(this.api.revisionen(this.revisionsseite))); }
    catch (e) { this.revisionsseite -= delta; this.fehler.set(this.text(e)); }
    finally { this.arbeitet.set(false); }
  }
  ziehen(event: DragEvent, o: Organisation): void {
    if (!this.bearbeitbar()) { event.preventDefault(); return; }
    this.gezogen = o; event.dataTransfer?.setData('text/plain', String(o.id));
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
    this.dragScroll.starten(event, this.struktur()?.nativeElement);
    event.stopPropagation();
  }
  erlauben(event: DragEvent): void { if (this.bearbeitbar() && this.gezogen) { event.preventDefault(); event.stopPropagation(); } }
  zugEnde(): void { this.gezogen = undefined; this.dragScroll.stoppen(); }
  async ablegen(event: DragEvent, parent: number | null): Promise<void> {
    event.preventDefault(); event.stopPropagation();
    const o = this.gezogen; this.zugEnde();
    if (!o || !this.bearbeitbar() || (o.parentId ?? null) === parent) return;
    await this.ausfuehren(async () => { await firstValueFrom(this.api.organisationSpeichern(o.name, o, parent)); this.geaendert.emit(); });
  }
  async orgSpeichern(): Promise<void> {
    if (!this.bearbeitbar()) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(this.api.organisationSpeichern(this.orgName.trim(), this.organisation, this.parentId));
      this.orgBearbeiten(); this.geaendert.emit();
    });
  }
  async orgLoeschen(o: Organisation): Promise<void> {
    if (!this.bearbeitbar()) return;
    if (!confirm(`Organisationseinheit „${o.name}“ löschen?`)) return;
    await this.ausfuehren(async () => {
      await firstValueFrom(this.api.organisationLoeschen(o)); this.orgBearbeiten(); this.geaendert.emit();
    });
  }
  private async ausfuehren(aktion: () => Promise<void>): Promise<void> {
    if (this.arbeitet()) return;
    this.arbeitet.set(true); this.fehler.set(''); this.meldung.set('');
    try { await aktion(); this.bereit.set(false); this.revisionsseite = 0; await this.laden(); this.meldung.set('Änderung gespeichert.'); }
    catch (e) { this.fehler.set(this.text(e)); }
    finally { this.arbeitet.set(false); }
  }
  private text(e: unknown): string {
    if (e instanceof ApiFehler) return [e.message, ...new Set(Object.values(e.felder))].join(' ');
    return e instanceof Error ? e.message : 'Die Änderung konnte nicht gespeichert werden.';
  }
}
