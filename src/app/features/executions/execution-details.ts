import { Component, inject, input, output, signal } from '@angular/core';
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
            [class.bg-amber-50]="run.status === 'QUEUED' || run.status === 'RUNNING' || run.status === 'CANCELLING'"
            [class.text-amber-700]="run.status === 'QUEUED' || run.status === 'RUNNING' || run.status === 'CANCELLING'"
            [class.bg-emerald-50]="run.status === 'SUCCEEDED'"
            [class.text-emerald-700]="run.status === 'SUCCEEDED'"
            [class.bg-rose-50]="run.status === 'FAILED'"
            [class.text-rose-700]="run.status === 'FAILED'"
            [class.bg-slate-100]="run.status === 'CANCELLED'"
            [class.text-slate-700]="run.status === 'CANCELLED'">
            {{ run.status }}
          </span>
        </header>

        <div class="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
          <div class="text-sm text-slate-600">
            Duration: <strong class="font-semibold text-slate-800">{{ duration(run) }}</strong>
            <span class="ml-2 text-xs text-slate-500">Timeout: {{ run.timeoutSeconds }}s</span>
          </div>
          <div class="flex gap-2">
            @if (run.status === 'QUEUED' || run.status === 'RUNNING') {
              <button type="button" class="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-white disabled:opacity-50" [disabled]="actionBusy()" (click)="cancel(run.id)">
                {{ actionBusy() ? 'Working…' : 'Cancel' }}
              </button>
            }
            @if (run.status === 'FAILED') {
              <button type="button" class="rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50" [disabled]="actionBusy()" (click)="retry(run.id)">
                {{ actionBusy() ? 'Retrying…' : 'Retry' }}
              </button>
            }
          </div>
        </div>
        @if (run.status === 'RUNNING' || run.status === 'CANCELLING') {
          <p class="text-xs text-slate-500">Cancellation takes effect after the active node finishes. It cannot undo external side effects already performed.</p>
        }
        @if (actionError()) {
          <div role="alert" class="rounded-2xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">{{ actionError() }}</div>
        }

        @if (run.errorMessage) {
          <div role="alert" class="rounded-2xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">
            {{ run.errorMessage }}
            @if (run.errorCode) { <span class="ml-1 font-mono text-xs">({{ run.errorCode }})</span> }
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
                      @if (node.durationMs !== null) {
                        <p class="mt-1 text-xs text-slate-500">Node duration: {{ nodeDuration(node.durationMs) }}</p>
                      }
                    </div>
                    <span class="rounded-full px-2 py-1 text-[11px] font-medium"
                      [class.bg-amber-50]="node.status === 'RUNNING'"
                      [class.text-amber-700]="node.status === 'RUNNING'"
                      [class.bg-emerald-50]="node.status === 'SUCCEEDED'"
                      [class.text-emerald-700]="node.status === 'SUCCEEDED'"
                      [class.bg-rose-50]="node.status === 'FAILED'"
                      [class.text-rose-700]="node.status === 'FAILED'"
                      [class.bg-slate-100]="node.status === 'PENDING' || node.status === 'SKIPPED'"
                      [class.text-slate-600]="node.status === 'PENDING' || node.status === 'SKIPPED'">
                      {{ node.status === 'SKIPPED' ? 'SKIPPED · branch not selected' : node.status }}
                    </span>
                  </div>
                  @if (node.errorMessage) {
                    <p class="mt-2 text-sm text-rose-700">{{ node.errorMessage }}</p>
                  }
                  @if (node.nodeType === 'http-request' && httpResponse(node.outputData); as response) {
                    <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600">
                      <span>HTTP status: <strong>{{ response.statusCode }}</strong></span>
                      <span>Response format: <strong>{{ response.format }}</strong></span>
                    </div>
                  }
                  <div class="mt-3 grid gap-3 sm:grid-cols-2">
                    <div>
                      <p class="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-500">Input</p>
                      <pre class="max-h-40 overflow-auto rounded-xl bg-slate-950 p-2 text-xs text-slate-100">{{ formatJson(node.inputData) }}</pre>
                    </div>
                    <div>
                      <p class="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-500">Output</p>
                      <pre class="max-h-40 overflow-auto rounded-xl bg-slate-950 p-2 text-xs text-slate-100">{{ formatJson(node.outputData) }}</pre>
                      @if (isOutputTruncated(node.outputData)) {
                        <p class="mt-1 text-xs text-slate-500">Output preview is limited to 30,000 characters.</p>
                      }
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
  readonly retryStarted = output<string>();

  protected readonly execution = signal<ExecutionResponse | null>(null);
  protected readonly nodes = signal<NodeExecutionResponse[]>([]);
  protected readonly logs = signal<ExecutionLogResponse[]>([]);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly actionBusy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly now = signal(Date.now());
  private readonly api = inject(ExecutionApiService);
  private readonly jsonPreviewCache = new WeakMap<object, { text: string; truncated: boolean }>();

  constructor() {
    timer(0, 1000).pipe(takeUntilDestroyed()).subscribe(() => {
      this.now.set(Date.now());
    });
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
        takeWhile(({ execution }) =>
          execution.status === 'QUEUED' || execution.status === 'RUNNING' || execution.status === 'CANCELLING', true
        )
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
    if (value == null) return '—';
    const preview = this.jsonPreview(value);
    return preview.truncated ? `${preview.text}\n… output truncated` : preview.text;
  }

  protected isOutputTruncated(value: unknown): boolean {
    return value != null && this.jsonPreview(value).truncated;
  }

  private jsonPreview(value: unknown): { text: string; truncated: boolean } {
    if (typeof value === 'object' && value !== null) {
      const cached = this.jsonPreviewCache.get(value);
      if (cached) return cached;
      const formatted = JSON.stringify(value, null, 2);
      const preview = {
        text: formatted.length > 30_000 ? formatted.slice(0, 30_000) : formatted,
        truncated: formatted.length > 30_000
      };
      this.jsonPreviewCache.set(value, preview);
      return preview;
    }
    const formatted = JSON.stringify(value, null, 2);
    return {
      text: formatted.length > 30_000 ? formatted.slice(0, 30_000) : formatted,
      truncated: formatted.length > 30_000
    };
  }

  protected httpResponse(value: unknown): { statusCode: number; format: string } | null {
    if (!value || typeof value !== 'object' || !('statusCode' in value) || !('body' in value)) return null;
    const response = value as { statusCode: unknown; body: unknown; responseFormat?: unknown };
    if (typeof response.statusCode !== 'number') return null;
    const format = response.responseFormat === 'JSON' || response.responseFormat === 'Text'
      ? response.responseFormat
      : typeof response.body === 'string' ? 'Text' : 'JSON';
    return { statusCode: response.statusCode, format };
  }

  protected nodeDuration(milliseconds: number): string {
    return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(2)} s`;
  }

  protected hasDetails(value: unknown): boolean {
    return value != null && typeof value === 'object' && Object.keys(value).length > 0;
  }

  protected duration(run: ExecutionResponse): string {
    const elapsed = run.durationMs ?? (run.startedAt ? Math.max(0, this.now() - Date.parse(run.startedAt)) : null);
    if (elapsed == null) return run.status === 'QUEUED' ? 'Queued' : '—';
    const seconds = Math.floor(elapsed / 1000);
    return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`;
  }

  protected cancel(executionId: string): void {
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.api.cancelExecution(executionId).subscribe({
      next: (run) => this.execution.set(run),
      error: () => {
        this.actionError.set('Unable to cancel this execution. Refresh and try again.');
        this.actionBusy.set(false);
      },
      complete: () => this.actionBusy.set(false)
    });
  }

  protected retry(executionId: string): void {
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.api.retryExecution(executionId).subscribe({
      next: (run) => this.retryStarted.emit(run.id),
      error: () => {
        this.actionError.set('Unable to retry this execution. Refresh and try again.');
        this.actionBusy.set(false);
      },
      complete: () => this.actionBusy.set(false)
    });
  }
}
