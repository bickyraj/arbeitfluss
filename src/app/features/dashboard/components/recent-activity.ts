import { Component, input } from '@angular/core';
import { AlertCircle, CheckCircle2, Clock3, Play, LucideAngularModule, type LucideIconData } from 'lucide-angular';
import type { ActivityItem } from '../../../core/models/dashboard.model';

@Component({
  selector: 'app-recent-activity',
  standalone: true,
  imports: [LucideAngularModule],
  template: `
    <aside class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
      <h2 class="text-lg font-semibold text-slate-900">Recent Activity</h2>

      <div class="mt-5 space-y-4">
        @for (item of activities(); track item.workflow + item.time) {
          <div class="flex gap-3">
            <div
              class="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl"
              [class.bg-emerald-50]="item.status === 'Active' || item.description.includes('completed')"
              [class.text-emerald-600]="item.status === 'Active' || item.description.includes('completed')"
              [class.bg-amber-50]="item.status === 'Paused'"
              [class.text-amber-600]="item.status === 'Paused'"
              [class.bg-rose-50]="item.status === 'Failed'"
              [class.text-rose-600]="item.status === 'Failed'"
            >
              <lucide-icon [img]="getIcon(item)" class="h-4 w-4" />
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between gap-2">
                <p class="truncate text-sm font-medium text-slate-800">{{ item.workflow }}</p>
                <span class="text-xs text-slate-400">{{ item.time }}</span>
              </div>
              <p class="mt-1 text-sm text-slate-600">{{ item.description }}</p>
            </div>
          </div>
        }
      </div>
    </aside>
  `,
  host: { class: 'block' }
})
export class RecentActivityComponent {
  activities = input.required<ActivityItem[]>();

  protected readonly activityIcon = Clock3;
  protected readonly successIcon = CheckCircle2;
  protected readonly startedIcon = Play;
  protected readonly failedIcon = AlertCircle;

  protected getIcon(item: ActivityItem): LucideIconData {
    if (item.status === 'Failed') {
      return this.failedIcon;
    }

    if (item.description.includes('started')) {
      return this.startedIcon;
    }

    if (item.description.includes('paused')) {
      return this.activityIcon;
    }

    return this.successIcon;
  }
}
