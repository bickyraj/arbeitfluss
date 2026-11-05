import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { LucideAngularModule, RefreshCw, Search, Activity, CheckCircle2, XCircle, Gauge } from 'lucide-angular';
import { catchError, EMPTY, merge, Subject, switchMap, timer } from 'rxjs';

import { ExecutionApiService } from '../../core/services/execution-api.service';
import type { ExecutionResponse, ExecutionStatus } from '../../core/models/execution.model';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';
import { ExecutionDetailsComponent } from './execution-details';

type ExecutionSortOption = 'newest' | 'oldest' | 'duration-desc' | 'status';
type StatusFilter = 'All' | ExecutionStatus;

@Component({
  selector: 'app-executions-page',
  standalone: true,
  imports: [MainLayoutComponent, LucideAngularModule, ExecutionDetailsComponent, DatePipe, RouterLink],
  template: `
    <app-main-layout [title]="'Executions'">
      <div class="space-y-6">
        <header class="flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:flex-row sm:items-center sm:justify-between sm:p-6">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">Observability</p>
            <h1 class="mt-2 text-2xl font-semibold tracking-tight text-slate-900">Execution History</h1>
            <p class="mt-1 text-sm text-slate-600">Monitor workflow runs, diagnose failures, and inspect execution payloads.</p>
          </div>
          <button type="button" class="inline-flex items-center gap-2 self-start rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50" (click)="refreshExecutions()">
            <lucide-icon [img]="refreshIcon" class="h-4 w-4" /> Refresh
          </button>
        </header>

        <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          @for (stat of stats(); track stat.label) {
            <article class="rounded-2xl border border-slate-200 bg-white p-5">
              <p class="text-sm text-slate-500">{{ stat.label }}</p>
              <div class="mt-3 flex items-center justify-between">
                <p class="text-3xl font-semibold tracking-tight text-slate-900">{{ stat.value }}</p>
                <lucide-icon [img]="stat.icon" class="h-5 w-5 text-indigo-600" />
              </div>
            </article>
          }
        </section>

        <section class="flex flex-col gap-3 rounded-3xl border border-slate-200 bg-white p-4 shadow-sm xl:flex-row xl:items-center xl:justify-between">
          <label class="flex w-full items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-500 xl:max-w-md">
            <lucide-icon [img]="searchIcon" class="h-4 w-4" />
            <input type="search" [value]="search()" class="w-full border-0 bg-transparent text-slate-700 outline-none placeholder:text-slate-400" placeholder="Search workflows" (input)="updateSearch($event)" />
          </label>
          <div class="flex flex-wrap items-center gap-3">
            <label class="flex items-center gap-2 text-sm text-slate-600">
              <span>Status</span>
              <select class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700" [value]="statusFilter()" (change)="updateStatusFilter($any($event.target).value)">
                <option value="All">All</option>
                <option value="QUEUED">Queued</option>
                <option value="RUNNING">Running</option>
                <option value="CANCELLING">Cancelling</option>
                <option value="SUCCEEDED">Succeeded</option>
                <option value="FAILED">Failed</option>
                <option value="CANCELLED">Cancelled</option>
              </select>
            </label>
            <label class="flex items-center gap-2 text-sm text-slate-600">
              <span>Sort</span>
              <select class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700" [value]="sortBy()" (change)="updateSort($any($event.target).value)">
                <option value="newest">Newest first</option>
                <option value="oldest">Oldest first</option>
                <option value="duration-desc">Longest duration</option>
                <option value="status">Status</option>
              </select>
            </label>
          </div>
        </section>

        @if (loadError()) {
          <div role="alert" class="rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">{{ loadError() }}</div>
        }

        <div class="grid gap-6 xl:grid-cols-[1.25fr_1fr]">
          <section class="overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm">
            <div class="overflow-x-auto">
              <table class="min-w-full text-left text-sm">
                <thead class="bg-slate-50 text-slate-600">
                  <tr>
                    <th class="px-5 py-3 font-medium">Workflow</th>
                    <th class="px-5 py-3 font-medium">Status</th>
                    <th class="px-5 py-3 font-medium">Started</th>
                    <th class="px-5 py-3 font-medium">Duration</th>
                    <th class="px-5 py-3 font-medium">Trigger</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-slate-200 text-slate-700">
                  @for (execution of executions(); track execution.id) {
                    <tr class="cursor-pointer transition hover:bg-slate-50" [class.bg-indigo-50]="currentExecutionId() === execution.id" (click)="selectExecution(execution.id)">
                      <td class="px-5 py-4">
                        <a class="font-medium text-slate-900 hover:text-indigo-600 hover:underline" [routerLink]="['/executions', execution.id]" (click)="$event.stopPropagation()">{{ execution.workflowName }}</a>
                        <p class="mt-1 text-xs text-slate-500">{{ execution.id }}</p>
                      </td>
                      <td class="px-5 py-4">
                        <span class="inline-flex rounded-full px-2.5 py-1 text-xs font-medium"
                          [class.bg-emerald-50]="execution.status === 'SUCCEEDED'" [class.text-emerald-700]="execution.status === 'SUCCEEDED'"
                          [class.bg-rose-50]="execution.status === 'FAILED'" [class.text-rose-700]="execution.status === 'FAILED'"
                          [class.bg-amber-50]="execution.status === 'QUEUED' || execution.status === 'RUNNING' || execution.status === 'CANCELLING'"
                          [class.text-amber-700]="execution.status === 'QUEUED' || execution.status === 'RUNNING' || execution.status === 'CANCELLING'"
                          [class.bg-slate-200]="execution.status === 'CANCELLED'" [class.text-slate-700]="execution.status === 'CANCELLED'">
                          {{ execution.status }}
                        </span>
                      </td>
                      <td class="whitespace-nowrap px-5 py-4">{{ execution.startedAt ? (execution.startedAt | date:'short') : '—' }}</td>
                      <td class="whitespace-nowrap px-5 py-4">{{ formatDuration(execution.durationMs) }}</td>
                      <td class="px-5 py-4">{{ execution.triggerType }}</td>
                    </tr>
                  } @empty {
                    <tr><td colspan="5" class="px-5 py-12 text-center text-slate-500">{{ loading() ? 'Loading executions…' : 'No executions found.' }}</td></tr>
                  }
                </tbody>
              </table>
            </div>
            <div class="flex flex-col gap-3 border-t border-slate-200 p-4 sm:flex-row sm:items-center sm:justify-between">
              <p class="text-sm text-slate-600">Showing {{ executions().length }} of {{ totalElements() }} executions</p>
              <div class="flex items-center gap-3">
                <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-600 disabled:opacity-40" [disabled]="page() === 0" (click)="changePage(page() - 1)">Previous</button>
                <span class="text-sm text-slate-600">Page {{ page() + 1 }} of {{ totalPages() }}</span>
                <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-600 disabled:opacity-40" [disabled]="page() + 1 >= totalPages()" (click)="changePage(page() + 1)">Next</button>
              </div>
            </div>
          </section>

          <aside class="min-h-72 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
            @if (currentExecutionId(); as executionId) {
              <app-execution-details [executionId]="executionId" (retryStarted)="onRetryStarted($event)" />
            } @else {
              <p class="py-12 text-center text-sm text-slate-500">Select an execution to inspect its status, outputs, and logs.</p>
            }
          </aside>
        </div>
      </div>
    </app-main-layout>
  `,
  host: { class: 'block' }
})
export class ExecutionsPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ExecutionApiService);
  private readonly reloads = new Subject<void>();

  protected readonly executions = signal<ExecutionResponse[]>([]);
  protected readonly focusedExecutionId = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly statusFilter = signal<StatusFilter>('All');
  protected readonly sortBy = signal<ExecutionSortOption>('newest');
  protected readonly page = signal(0);
  protected readonly totalPages = signal(1);
  protected readonly totalElements = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly selectedExecutionId = signal<string | null>(null);
  protected readonly pageSize = 20;

  protected readonly refreshIcon = RefreshCw;
  protected readonly searchIcon = Search;

  protected readonly currentExecutionId = computed(() =>
    this.focusedExecutionId() ?? this.selectedExecutionId() ?? this.executions()[0]?.id ?? null
  );

  protected readonly stats = computed(() => {
    const records = this.executions();
    return [
      { label: 'Total executions', value: this.totalElements().toString(), icon: Activity },
      { label: 'Succeeded on page', value: records.filter((run) => run.status === 'SUCCEEDED').length.toString(), icon: CheckCircle2 },
      { label: 'Failed on page', value: records.filter((run) => run.status === 'FAILED').length.toString(), icon: XCircle },
      { label: 'Active on page', value: records.filter((run) => this.isActive(run.status)).length.toString(), icon: Gauge }
    ];
  });

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.focusedExecutionId.set(params.get('executionId'));
    });
    merge(timer(0, 3000), this.reloads).pipe(
      switchMap(() => {
        this.loading.set(true);
        const status = this.statusFilter();
        return this.api.listExecutions(
          this.page(),
          this.pageSize,
          this.search(),
          status === 'All' ? null : status,
          this.sortBy()
        ).pipe(catchError(() => {
          this.loadError.set('Unable to load execution history. Please try again.');
          this.loading.set(false);
          return EMPTY;
        }));
      }),
      takeUntilDestroyed()
    ).subscribe((response) => {
      this.loadError.set(null);
      this.executions.set(response.content);
      this.totalElements.set(response.totalElements);
      this.totalPages.set(Math.max(1, response.totalPages));
      this.loading.set(false);
      if (!this.selectedExecutionId() && response.content.length) {
        this.selectedExecutionId.set(response.content[0].id);
      }
    });
  }

  protected updateSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
    this.page.set(0);
    this.reloads.next();
  }

  protected updateStatusFilter(value: string): void {
    this.statusFilter.set((value as StatusFilter) || 'All');
    this.page.set(0);
    this.reloads.next();
  }

  protected updateSort(value: string): void {
    this.sortBy.set((value as ExecutionSortOption) || 'newest');
    this.page.set(0);
    this.reloads.next();
  }

  protected changePage(page: number): void {
    this.page.set(page);
    this.reloads.next();
  }

  protected selectExecution(executionId: string): void {
    this.focusedExecutionId.set(null);
    this.selectedExecutionId.set(executionId);
  }

  protected refreshExecutions(): void {
    this.reloads.next();
  }

  protected onRetryStarted(executionId: string): void {
    this.selectedExecutionId.set(executionId);
    this.focusedExecutionId.set(executionId);
    this.page.set(0);
    this.reloads.next();
    this.router.navigate([], { queryParams: { executionId }, queryParamsHandling: 'merge' });
  }

  protected formatDuration(durationMs: number | null): string {
    if (durationMs == null) return '—';
    const totalSeconds = Math.floor(durationMs / 1000);
    return `${Math.floor(totalSeconds / 60)}m ${String(totalSeconds % 60).padStart(2, '0')}s`;
  }

  private isActive(status: ExecutionStatus): boolean {
    return status === 'QUEUED' || status === 'RUNNING' || status === 'CANCELLING';
  }
}
