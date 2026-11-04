import { Component, input } from '@angular/core';
import { LucideAngularModule, type LucideIconData } from 'lucide-angular';

@Component({
  selector: 'app-stat-card',
  standalone: true,
  imports: [LucideAngularModule],
  template: `
    <article class="rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_10px_30px_rgba(15,23,42,0.04)] transition hover:-translate-y-0.5 hover:shadow-[0_16px_35px_rgba(15,23,42,0.08)]">
      <div class="flex items-center justify-between gap-3">
        <div>
          <p class="text-sm font-medium text-slate-500">{{ title() }}</p>
          <p class="mt-3 text-3xl font-semibold tracking-tight text-slate-900">{{ value() }}</p>
        </div>
        <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
          <lucide-icon [img]="icon()" class="h-5 w-5" />
        </div>
      </div>
    </article>
  `,
  host: { class: 'block' }
})
export class StatCardComponent {
  title = input.required<string>();
  value = input.required<string>();
  icon = input.required<LucideIconData>();
}
