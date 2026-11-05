import { Component, inject, input, signal } from '@angular/core';
import { forkJoin, switchMap, takeWhile, tap, timer } from 'rxjs';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';

import { ExecutionApiService } from '../../core/services/execution-api.service';
import type {
  ExecutionLogResponse,
  ExecutionResponse,
  NodeExecutionResponse
} from '../../core/models/execution.model';

@Component({
  selector: 'app-execution-details',
  standalone: true,
  template: `
    @if (errorMessage()) {
      <div role="alert" class="rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">
        {{ errorMessage() }}
      </div>
    } @else if (execution(); as run) {
      <div class="space-y-5">
        <header class="flex flex-wrap items-start justify-between gap-3">
          <div class="min-w-0">
            <p class="text-xs font-medium uppercase tracking-[0.14em] text-slate-500">Execution details</p>
            <h2 class="mt-1 break-all text-lg font-semibold text-slate-900">{{ run.workflowName }}</h2>
            <p class="mt-1 break-all font-mono text-xs text-slate-500">{{ run.id }}</p>
          </div>
          <span class="rounded-full px-2.5 py-1 text-xs font-semibold"
            [class.bg-amber-50]="run.status === 'QUEUED' || run.status === 'RUNNING'"
            [class.text-amber-700]="run.status === 'QUEUED' || run.status === 'RUNNING'"
            [class.bg-emerald-50]="run.status === 'SUCCEEDED'"
            [class.text-emerald-700]="run.status === 'SUCCEEDED'"
            [class.bg-rose-50]="run.status === 'FAILED'"
            [class.text-rose-700]="run.status === 'FAILED'"
            [class.bg-slate-100]="run.status === 'CANCELLED'"
            [class.text-slate-700]="run.status === 'CANCELLED'">
            {{ run.status }}
          </span>
        </header>

        @if (run.errorMessage) {
          <div role="alert" class="rounded-2xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">
            {{ run.errorMessage }}
          </div>
        }

        <section>
          <h3 class="mb-2 text-xs font-semibold uppercase tracking-[0.12em] text-slate-500">Node executions</h3>
          @if (nodes().length) {
            <div class="space-y-3">
              @for (node of nodes(); track node.id) {
                <article class="rounded-2xl border border-slate-200 bg-white p-3">
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <div class="min-w-0">
                      <p class="break-all text-sm font-medium text-slate-900">{{ node.workflowNodeId }}</p>
                      <p class="text-xs text-slate-500">{{ node.nodeType }}</p>
                    </div>
                    <span class="rounded-full bg-slate-100 px-2 py-1 text-[11px] font-medium text-slate-700">{{ node.status }}</span>
                  </div>
                  @if (node.errorMessage) {
                    <p class="mt-2 text-sm text-rose-700">{{ node.errorMessage }}</p>
                  }
                  <div class="mt-3 grid gap-3 sm:grid-cols-2">
                    <div>
                      <p class="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-500">Input</p>
                      <pre class="max-h-40 overflow-auto rounded-xl bg-slate-950 p-2 text-xs text-slate-100">{{ formatJson(node.inputData) }}</pre>
                    </div>
                    <div>
                      <p class="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-500">Output</p>
                      <pre class="max-h-40 overflow-auto rounded-xl bg-slate-950 p-2 text-xs text-slate-100">{{ formatJson(node.outputData) }}</pre>
                    </div>
                  </div>
                </article>
              }
            </div>
          } @else {
            <p class="rounded-xl bg-slate-50 p-3 text-sm text-slate-500">Waiting for node execution records…</p>
          }
        </section>

        <section>
          <h3 class="mb-2 text-xs font-semibold uppercase tracking-[0.12em] text-slate-500">Execution logs</h3>
          @if (logs().length) {
            <ol class="max-h-56 space-y-2 overflow-auto">
              @for (log of logs(); track log.id) {
                <li class="rounded-xl border border-slate-200 bg-slate-50 p-3">
                  <div class="flex flex-wrap justify-between gap-2 text-[11px] font-medium uppercase tracking-wide text-slate-500">
                    <span>{{ log.level }}</span>
                    <time>{{ log.createdAt | date: 'medium' }}</time>
                  </div>
                  <p class="mt-1 break-words text-sm text-slate-700">{{ log.message }}</p>
                  @if (hasDetails(log.details)) {
                    <pre class="mt-2 overflow-auto text-xs text-slate-500">{{ formatJson(log.details) }}</pre>
                  }
                </li>
              }
            </ol>
          } @else {
            <p class="rounded-xl bg-slate-50 p-3 text-sm text-slate-500">No execution logs yet.</p>
          }
        </section>
      </div>
    } @else {
      <p class="text-sm text-slate-500">{{ loading() ? 'Loading execution details…' : 'Execution details are not available.' }}</p>
    }
  `,
  imports: [DatePipe],
})
export class ExecutionDetailsComponent {
  readonly executionId = input.required<string>();

  protected readonly execution = signal<ExecutionResponse | null>(null);
  protected readonly nodes = signal<NodeExecutionResponse[]>([]);
  protected readonly logs = signal<ExecutionLogResponse[]>([]);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly loading = signal(true);
  private readonly api = inject(ExecutionApiService);

  constructor() {
    toObservable(this.executionId).pipe(
      tap(() => {
        this.loading.set(true);
        this.errorMessage.set(null);
        this.execution.set(null);
        this.nodes.set([]);
        this.logs.set([]);
      }),
      switchMap((id) => timer(0, 1500).pipe(
        switchMap(() => forkJoin({
          execution: this.api.getExecution(id),
          nodes: this.api.getNodeExecutions(id),
          logs: this.api.getExecutionLogs(id)
        })),
        takeWhile(({ execution }) => execution.status === 'QUEUED' || execution.status === 'RUNNING', true)
      )),
      takeUntilDestroyed()
    ).subscribe({
      next: (result) => {
        this.execution.set(result.execution);
        this.nodes.set(result.nodes);
        this.logs.set(result.logs);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set('Unable to load execution details. Please try again.');
      }
    });
  }

  protected formatJson(value: unknown): string {
    return value == null ? '—' : JSON.stringify(value, null, 2);
  }

  protected hasDetails(value: unknown): boolean {
    return value != null && typeof value === 'object' && Object.keys(value).length > 0;
  }
}
