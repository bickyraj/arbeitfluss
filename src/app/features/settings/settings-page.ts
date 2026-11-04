import { Component } from '@angular/core';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';
import { ComingSoonComponent } from '../../shared/components/coming-soon/coming-soon';

@Component({
  selector: 'app-settings-page',
  standalone: true,
  imports: [MainLayoutComponent, ComingSoonComponent],
  template: `
    <app-main-layout [title]="'Settings'">
      <app-coming-soon
        title="Settings"
        description="Platform preferences and workspace configuration will be introduced in the next milestone."
      />
    </app-main-layout>
  `,
  host: { class: 'block' }
})
export class SettingsPageComponent {}
