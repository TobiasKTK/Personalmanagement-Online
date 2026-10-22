/** Scrollt während eines nativen Drag-and-drop auch bei stillgehaltenem Mauszeiger. */
export class OrganisationsDragScroll {
  private aufraeumen?: () => void;

  starten(event: DragEvent, struktur?: HTMLElement): void {
    this.stoppen();
    const dokument = (event.currentTarget as HTMLElement).ownerDocument;
    const fenster = dokument.defaultView;
    if (!fenster) return;

    let position: { x: number; y: number } | undefined;
    let frame: number | undefined;
    let letzterZeitpunkt = 0;
    let aktiv = true;

    const scrollen = (zeitpunkt: number): void => {
      if (!aktiv || !position) { frame = undefined; return; }
      const sekunden = letzterZeitpunkt ? Math.min(zeitpunkt - letzterZeitpunkt, 32) / 1000 : 1 / 60;
      letzterZeitpunkt = zeitpunkt;
      const { x, y } = position;
      let innenVertikal = false;

      if (struktur) {
        // Nur die sichtbaren Ränder zählen, auch wenn das Organigramm höher als das Fenster ist.
        const rect = struktur.getBoundingClientRect();
        const links = Math.max(0, rect.left), rechts = Math.min(fenster.innerWidth, rect.right);
        const oben = Math.max(0, rect.top), unten = Math.min(fenster.innerHeight, rect.bottom);
        if (x >= links && x <= rechts && y >= oben && y <= unten) {
          const vorher = struktur.scrollTop;
          struktur.scrollBy({
            left: this.tempo(x, links, rechts) * sekunden,
            top: this.tempo(y, oben, unten) * sekunden,
            behavior: 'instant',
          });
          innenVertikal = struktur.scrollTop !== vorher;
        }
      }
      // Wenn der innere Bereich nicht weiter scrollt, übernimmt die Seite.
      if (!innenVertikal) {
        fenster.scrollBy({ top: this.tempo(y, 0, fenster.innerHeight) * sekunden, behavior: 'instant' });
      }
      if (aktiv) frame = fenster.requestAnimationFrame(scrollen);
    };
    const bewegen = (e: DragEvent): void => {
      if (!aktiv) return;
      position = { x: e.clientX, y: e.clientY };
      if (frame === undefined) frame = fenster.requestAnimationFrame(scrollen);
    };
    const verlassen = (e: DragEvent): void => {
      if (e.relatedTarget === null && (e.clientX <= 0 || e.clientY <= 0
          || e.clientX >= fenster.innerWidth || e.clientY >= fenster.innerHeight)) {
        position = undefined;
        letzterZeitpunkt = 0;
      }
    };
    const ende = (): void => this.stoppen();
    const ablegen = (e: DragEvent): void => { e.preventDefault(); this.stoppen(); };
    const taste = (e: KeyboardEvent): void => { if (e.key === 'Escape') this.stoppen(); };

    // Capture erreicht auch die Karten, deren eigene Handler die Weitergabe unterbinden.
    dokument.addEventListener('dragenter', bewegen, true);
    dokument.addEventListener('dragover', bewegen, true);
    dokument.addEventListener('dragleave', verlassen, true);
    dokument.addEventListener('drop', ablegen, true);
    dokument.addEventListener('dragend', ende, true);
    dokument.addEventListener('keydown', taste, true);
    dokument.addEventListener('keyup', taste, true);
    dokument.addEventListener('mouseup', ende, true);
    fenster.addEventListener('blur', ende);
    this.aufraeumen = () => {
      aktiv = false;
      position = undefined;
      if (frame !== undefined) fenster.cancelAnimationFrame(frame);
      dokument.removeEventListener('dragenter', bewegen, true);
      dokument.removeEventListener('dragover', bewegen, true);
      dokument.removeEventListener('dragleave', verlassen, true);
      dokument.removeEventListener('drop', ablegen, true);
      dokument.removeEventListener('dragend', ende, true);
      dokument.removeEventListener('keydown', taste, true);
      dokument.removeEventListener('keyup', taste, true);
      dokument.removeEventListener('mouseup', ende, true);
      fenster.removeEventListener('blur', ende);
    };
  }

  stoppen(): void { this.aufraeumen?.(); this.aufraeumen = undefined; }

  private tempo(position: number, anfang: number, ende: number): number {
    if (position < anfang || position > ende || ende <= anfang) return 0;
    const rand = Math.min(72, (ende - anfang) / 2);
    if (position < anfang + rand) return -900 * (1 - (position - anfang) / rand);
    if (position > ende - rand) return 900 * (1 - (ende - position) / rand);
    return 0;
  }
}
