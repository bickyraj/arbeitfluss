import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';

import { AdminAuthService } from '../../core/services/admin-auth.service';

@Component({
  selector: 'app-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="flex min-h-screen items-center justify-center bg-slate-950 px-4 py-10">
      <section class="w-full max-w-md rounded-3xl border border-slate-800 bg-white p-8 shadow-2xl">
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-indigo-600">FlowForge</p>
        <h1 class="mt-3 text-2xl font-semibold text-slate-900">Administrator sign in</h1>
        <p class="mt-2 text-sm leading-6 text-slate-600">
          Enter the administrator token configured for this FlowForge deployment.
          It is kept in memory only and must be entered again after a page reload.
        </p>
        <form class="mt-6 space-y-4" (submit)="signIn($event)">
          <label class="block text-sm font-medium text-slate-700" for="admin-token">Administrator token</label>
          <input
            id="admin-token"
            type="password"
            autocomplete="current-password"
            class="w-full rounded-xl border border-slate-300 px-3 py-2.5 text-sm outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
            [value]="token()"
            (input)="updateToken($event)"
          />
          @if (errorMessage()) {
            <p role="alert" class="text-sm text-rose-700">{{ errorMessage() }}</p>
          }
          <button
            type="submit"
            class="w-full rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-500 disabled:cursor-not-allowed disabled:opacity-50"
            [disabled]="loading() || !token().trim()"
          >
            {{ loading() ? 'Verifying…' : 'Sign in' }}
          </button>
        </form>
      </section>
    </main>
  `
})
export class LoginPageComponent {
  private readonly auth = inject(AdminAuthService);
  private readonly router = inject(Router);

  protected readonly token = signal('');
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected updateToken(event: Event): void {
    this.token.set((event.target as HTMLInputElement | null)?.value ?? '');
    this.errorMessage.set(null);
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    const token = this.token().trim();
    if (!token || this.loading()) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);
    this.auth.signIn(token).subscribe({
      next: () => {
        this.loading.set(false);
        void this.router.navigateByUrl('/dashboard');
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        this.errorMessage.set(error.status === 401
          ? 'The administrator token is invalid.'
          : 'Unable to verify the token. Check the server connection and try again.');
      }
    });
  }
}
