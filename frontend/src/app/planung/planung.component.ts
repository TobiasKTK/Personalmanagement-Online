import { Component } from '@angular/core';
import { VertretungComponent } from './vertretung.component';
import { FeiertagskalenderComponent } from './feiertagskalender.component';
import { BesetzungComponent } from './besetzung.component';
@Component({ selector: 'app-planung', standalone: true, imports: [VertretungComponent, FeiertagskalenderComponent, BesetzungComponent], template: `<section class="team-panel administration"><h1>Planung verwalten</h1><app-vertretung /><app-feiertagskalender /><app-besetzung /></section>` })
export class PlanungComponent {}
