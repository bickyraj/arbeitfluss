import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-coming-soon',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="mx-auto max-w-2xl rounded-3xl border border-slate-200 bg-white p-8 text-center shadow-sm sm:p-10">
      <div class="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-indigo-50 text-indigo-600">
        <svg viewBox="0 0 24 24" aria-hidden="true" class="h-7 w-7 fill-none stroke-current stroke-[1.8]">
          <path d="M12 2v7M9 12h6M7.5 4.5l9 15M16.5 4.5l-9 15" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <h2 class="mt-6 text-2xl font-semibold tracking-tight text-slate-900">{{ title() }}</h2>
      <p class="mt-3 text-base text-slate-600">{{ description() }}</p>
      <a
        routerLink="/dashboard"
        class="mt-6 inline-flex items-center justify-center rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2"
      >
        Back to dashboard
      </a>
    </div>
  `,
  host: { class: 'block' }
})
export class ComingSoonComponent {
  title = input('Coming soon');
  description = input('This area is being prepared for the next milestone.');
}
