import { Component, input, signal } from '@angular/core';
import { Activity, FolderKanban, LayoutGrid, Settings, Workflow } from 'lucide-angular';
import { SidebarComponent, type SidebarItem } from '../sidebar/sidebar';
import { TopbarComponent } from '../topbar/topbar';

@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [SidebarComponent, TopbarComponent],
  template: `
    <div class="min-h-screen bg-[#F8FAFC] text-slate-900">
      <div class="flex min-h-screen">
        <app-sidebar [items]="navItems" [mobileOpen]="sidebarOpen()" (closeMobile)="closeSidebar()" />

        <div class="flex min-h-screen min-w-0 flex-1 flex-col">
          <app-topbar [title]="title()" (toggleSidebar)="toggleSidebar()" />

          <main class="flex-1 p-4 sm:p-6 lg:p-8">
            <ng-content />
          </main>
        </div>
      </div>
    </div>
  `,
  host: { class: 'block' }
})
export class MainLayoutComponent {
  title = input('Overview');

  protected readonly sidebarOpen = signal(false);

  protected readonly navItems: SidebarItem[] = [
    { label: 'Overview', route: '/dashboard', icon: LayoutGrid },
    { label: 'Workflows', route: '/workflows', icon: Workflow },
    { label: 'Executions', route: '/executions', icon: Activity },
    { label: 'Templates', route: '/templates', icon: FolderKanban },
    { label: 'Settings', route: '/settings', icon: Settings }
  ];

  protected toggleSidebar(): void {
    this.sidebarOpen.update((value) => !value);
  }

  protected closeSidebar(): void {
    this.sidebarOpen.set(false);
  }
}
