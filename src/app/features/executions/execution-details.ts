import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { catchError, EMPTY, expand, finalize, forkJoin, map, merge, of, Subject, switchMap, takeUntil, timer } from 'rxjs';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';

import { ExecutionApiService } from '../../core/services/execution-api.service';
import type { ExecutionGraph, ExecutionLogResponse, ExecutionResponse, NodeExecutionResponse } from '../../core/models/execution.model';
import { ExecutionGraphComponent } from './execution-graph';
import { ExecutionJsonViewComponent } from './execution-json-view';
import { ExecutionLogListComponent } from './execution-log-list';
import { formatDuration, inspectionText } from './inspection-data';

let inspectorInstance = 0;
type InspectorTab = 'overview' | 'input' | 'output' | 'logs';

@Component({
  selector: 'app-execution-details',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './execution-details.html',
  styleUrl: './execution-details.css',
  standalone: true,
  imports: [DatePipe, RouterLink, ExecutionGraphComponent, ExecutionJsonViewComponent, ExecutionLogListComponent]
})
export class ExecutionDetailsComponent {
  readonly executionId = input.required<string>();
  readonly fullPage = input(false);
  readonly retryStarted = output<string>();
  protected readonly execution = signal<ExecutionResponse | null>(null);
  protected readonly nodes = signal<NodeExecutionResponse[]>([]);
  protected readonly logs = signal<ExecutionLogResponse[]>([]);
  protected readonly graph = signal<ExecutionGraph | null>(null);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly updateWarning = signal<string | null>(null);
  protected readonly graphError = signal(false);
  protected readonly loading = signal(true);
  protected readonly actionBusy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly tab = signal<InspectorTab>('overview');
  protected readonly tabs: InspectorTab[] = ['overview', 'input', 'output', 'logs'];
  protected readonly panelId = `node-inspection-${++inspectorInstance}`;
  protected readonly text = inspectionText;
  protected readonly nodeDuration = formatDuration;
  private readonly now = signal(Date.now());
  private readonly api = inject(ExecutionApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly reloads = new Subject<void>();
  private readonly resetActions = new Subject<void>();
  protected readonly choices = computed(() => {
    const snapshot = this.graph();
    const choices = snapshot?.nodes.map(n => ({ id: n.id, name: n.name || n.id, type: n.type })) ?? [];
    const ids = new Set(choices.map(n => n.id));
    return [...choices, ...this.nodes().filter(n => !ids.has(n.workflowNodeId)).map(n => ({ id: n.workflowNodeId, name: n.workflowNodeId, type: n.nodeType }))];
  });
  protected readonly selected = computed(() => this.nodes().find(n => n.workflowNodeId === this.selectedId()) ?? null);
  protected readonly selectedMetadata = computed(() => this.choices().find(n => n.id === this.selectedId()) ?? null);
  protected readonly selectedLogs = computed(() => this.selected() ? this.logs().filter(log => log.nodeExecutionId === this.selected()!.id) : []);
  protected readonly nodeLabels = computed(() => Object.fromEntries(this.nodes().map(node => [node.id, this.nodeName(node.workflowNodeId)])));
  protected readonly finishedNodes = computed(() => this.nodes().filter(n => ['SUCCEEDED', 'FAILED', 'SKIPPED'].includes(n.status)).length);
  protected readonly active = computed(() => this.isActive(this.execution()?.status));
  protected readonly httpResponse = computed(() => {
    const value = this.selected()?.outputData;
    if (!value || typeof value !== 'object' || !('statusCode' in value) || !('body' in value)) return null;
    const response = value as { statusCode: unknown; responseFormat?: unknown; body: unknown };
    return typeof response.statusCode === 'number' ? { statusCode: response.statusCode,
      format: response.responseFormat === 'JSON' || response.responseFormat === 'Text' ? response.responseFormat : typeof response.body === 'string' ? 'Text' : 'JSON' } : null;
  });

  constructor() {
    toObservable(this.executionId).pipe(
      switchMap(id => {
        this.resetActions.next();
        this.execution.set(null); this.nodes.set([]); this.logs.set([]); this.graph.set(null);
        this.selectedId.set(null); this.tab.set('overview'); this.errorMessage.set(null);
        this.graphError.set(false); this.updateWarning.set(null); this.actionError.set(null); this.loading.set(true);
        return merge(of(undefined), this.reloads).pipe(switchMap(() => timer(0).pipe(
          switchMap(() => this.loadCycle(id, true)),
          // Each cycle finishes before the next is scheduled: slow requests are never overlapped/cancelled by a timer.
          expand(run => this.isActive(run?.status) ? timer(1500).pipe(switchMap(() => this.loadCycle(id, false))) : EMPTY)
        )));
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe();
  }

  private loadCycle(id: string, requestGraph: boolean) {
    // Read status first; terminal node/log reads then happen after terminal state has committed.
    return this.api.getExecution(id).pipe(
      catchError(() => of(null)),
      switchMap(run => {
        if (!run) {
          this.loading.set(false);
          if (this.execution()) this.updateWarning.set('Unable to refresh. Showing the last loaded data; active runs will retry.');
          else this.errorMessage.set('Unable to load execution details. Check your access and try again.');
          return of(this.execution());
        }
        return forkJoin({
          nodes: this.api.getNodeExecutions(id).pipe(catchError(() => of(null))),
          logs: this.api.getExecutionLogs(id).pipe(catchError(() => of(null))),
          graph: requestGraph && this.graph() === null
            ? this.api.getExecutionGraph(id).pipe(catchError(() => of(null))) : of(this.graph())
        }).pipe(map(result => {
          this.execution.set(run); this.now.set(Date.now()); this.loading.set(false); this.errorMessage.set(null);
          const warnings: string[] = [];
          if (result.nodes !== null) this.nodes.set(result.nodes); else warnings.push('Node data could not be refreshed.');
          if (result.logs !== null) this.logs.set(result.logs); else warnings.push('Logs could not be refreshed.');
          this.updateWarning.set(warnings.length ? `${warnings.join(' ')} Last loaded values are retained. Use Refresh to retry.` : null);
          if (result.graph !== null) { this.graph.set(result.graph); this.graphError.set(false); }
          else if (requestGraph) this.graphError.set(true);
          if (!this.selectedId()) {
            this.selectedId.set(this.nodes().find(n => n.status === 'FAILED')?.workflowNodeId
              ?? this.nodes().find(n => n.status === 'RUNNING')?.workflowNodeId ?? this.choices()[0]?.id ?? null);
          }
          return run;
        }));
      })
    );
  }

  protected nodeName(id: string): string { return this.graph()?.nodes.find(n => n.id === id)?.name || id; }
  protected selectNode(id: string): void { this.selectedId.set(id); this.tab.set('overview'); }
  protected selectLogNode(nodeExecutionId: string): void {
    const node = this.nodes().find(n => n.id === nodeExecutionId);
    if (node) { this.selectedId.set(node.workflowNodeId); this.tab.set('logs'); }
  }
  protected refresh(): void { this.reloads.next(); }
  private isActive(status: string | undefined): boolean { return status === 'QUEUED' || status === 'RUNNING' || status === 'CANCELLING'; }
  protected duration(run: ExecutionResponse): string {
    const end = run.completedAt ? Date.parse(run.completedAt) : this.isActive(run.status) ? this.now() : null;
    const elapsed = run.durationMs ?? (run.startedAt && end !== null ? Math.max(0, end - Date.parse(run.startedAt)) : null);
    return formatDuration(elapsed);
  }

  protected cancel(id: string): void {
    if (this.actionBusy() || !this.execution() || this.execution()!.id !== id || !['QUEUED', 'RUNNING'].includes(this.execution()!.status)) return;
    this.actionBusy.set(true); this.actionError.set(null);
    this.api.cancelExecution(id).pipe(takeUntil(this.resetActions), takeUntilDestroyed(this.destroyRef), finalize(() => this.actionBusy.set(false))).subscribe({
      next: run => { this.execution.set(run); this.refresh(); },
      error: () => { this.actionError.set('Unable to cancel. The execution may have changed; refresh and try again.'); this.refresh(); }
    });
  }
  protected retry(id: string): void {
    if (this.actionBusy() || this.execution()?.id !== id || this.execution()?.status !== 'FAILED') return;
    this.actionBusy.set(true); this.actionError.set(null);
    this.api.retryExecution(id).pipe(takeUntil(this.resetActions), takeUntilDestroyed(this.destroyRef), finalize(() => this.actionBusy.set(false))).subscribe({
      next: run => this.retryStarted.emit(run.id),
      error: () => this.actionError.set('Unable to retry. The execution may have changed; refresh and try again.')
    });
  }
}
