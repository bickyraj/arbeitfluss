import { Component, input } from '@angular/core';
import { Ellipsis, LucideAngularModule } from 'lucide-angular';
import type { WorkflowSummary } from '../../../core/models/dashboard.model';

@Component({
  selector: 'app-recent-workflows',
  standalone: true,
  imports: [LucideAngularModule],
  template: `
    <section class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
      <div class="flex items-center justify-between gap-3">
        <div>
          <h2 class="text-lg font-semibold text-slate-900">Recent Workflows</h2>
        </div>
        <a href="/dashboard" class="text-sm font-medium text-indigo-600 hover:text-indigo-500">View all workflows</a>
      </div>

      <div class="mt-5 overflow-x-auto">
        <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
          <thead>
            <tr class="text-slate-500">
              <th class="pb-3 pr-4 font-medium">Workflow</th>
              <th class="pb-3 pr-4 font-medium">Status</th>
              <th class="pb-3 pr-4 font-medium">Last execution</th>
              <th class="pb-3 pr-4 font-medium">Success rate</th>
              <th class="pb-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            @for (workflow of workflows(); track workflow.name) {
              <tr class="align-middle">
                <td class="py-3 pr-4 font-medium text-slate-800">{{ workflow.name }}</td>
                <td class="py-3 pr-4">
                  <span
                    class="inline-flex rounded-full px-2.5 py-1 text-xs font-medium"
                    [class.bg-emerald-50]="workflow.status === 'Active'"
                    [class.text-emerald-700]="workflow.status === 'Active'"
                    [class.bg-amber-50]="workflow.status === 'Paused'"
                    [class.text-amber-700]="workflow.status === 'Paused'"
                    [class.bg-rose-50]="workflow.status === 'Failed'"
                    [class.text-rose-700]="workflow.status === 'Failed'"
                  >
                    {{ workflow.status }}
                  </span>
                </td>
                <td class="py-3 pr-4 text-slate-600">{{ workflow.lastExecution }}</td>
                <td class="py-3 pr-4 text-slate-700">{{ workflow.successRate }}%</td>
                <td class="py-3 text-right">
                  <button type="button" class="inline-flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 transition hover:bg-slate-100 hover:text-slate-700" aria-label="Workflow actions">
                    <lucide-icon [img]="ellipsisIcon" class="h-4 w-4" />
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>
  `,
  host: { class: 'block' }
})
export class RecentWorkflowsComponent {
  workflows = input.required<WorkflowSummary[]>();
  protected readonly ellipsisIcon = Ellipsis;
}
