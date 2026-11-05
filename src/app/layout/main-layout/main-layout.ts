import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Activity, FolderKanban, LayoutGrid, Settings, Workflow } from 'lucide-angular';
import { SidebarComponent, type SidebarItem } from '../sidebar/sidebar';
import { TopbarComponent } from '../topbar/topbar';
import { AdminAuthService } from '../../core/services/admin-auth.service';

@Component({
  selector: 'app-main-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SidebarComponent, TopbarComponent],
  template: `
    <div class="min-h-screen bg-[#F8FAFC] text-slate-900">
      <div class="flex min-h-screen">
        <app-sidebar [items]="navItems" [mobileOpen]="sidebarOpen()" (closeMobile)="closeSidebar()" />

        <div class="flex min-h-screen min-w-0 flex-1 flex-col">
          <div class="flex items-center justify-between border-b border-slate-200 bg-white pr-4">
            <app-topbar class="min-w-0 flex-1" [title]="title()" (toggleSidebar)="toggleSidebar()" />
            <button type="button" class="rounded-lg px-3 py-2 text-xs font-medium text-slate-600 hover:bg-slate-100"
                    (click)="signOut()">Sign out
            </button>
          </div>

          <main class="flex-1 p-4 sm:p-6 lg:p-8">
            <ng-content />
          </main>
        </div>
      </div>
    </div>
  `,
  standalone: true,
  host: { class: 'block' }
})
export class MainLayoutComponent {
  private readonly auth = inject(AdminAuthService);
  private readonly router = inject(Router);

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

  protected signOut(): void {
    this.auth.signOut();
    void this.router.navigateByUrl('/login');
  }
}
