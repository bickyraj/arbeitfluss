import { afterNextRender, ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import type { ExecutionGraph, ExecutionGraphConnection, ExecutionGraphNode, ExecutionLogResponse, NodeExecutionResponse } from '../../core/models/execution.model';
import { inspectionText } from './inspection-data';

let graphInstance = 0;

@Component({
  selector: 'app-execution-graph',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 px-4 py-3">
      <div><h3 class="text-sm font-semibold text-slate-900">Execution snapshot</h3>
        <p class="mt-1 text-xs text-slate-500">Read-only · Select a node to inspect its persisted data</p></div>
      <div class="flex items-center gap-2 text-xs text-slate-600">
        <button type="button" aria-label="Zoom out" class="rounded-lg border border-slate-200 px-2 py-1.5"
                (click)="zoomBy(-0.15)">−
        </button>
        <span class="w-10 text-center">{{ percent() }}%</span>
        <button type="button" aria-label="Zoom in" class="rounded-lg border border-slate-200 px-2 py-1.5"
                (click)="zoomBy(0.15)">+
        </button>
        <button type="button" class="rounded-lg border border-slate-200 px-2 py-1.5" (click)="fit()">Fit graph</button>
      </div>
    </div>
    <div #viewport class="graph-viewport" tabindex="0" aria-label="Read-only workflow execution graph">
      <div [style.width.px]="layout().width * zoom()" [style.height.px]="layout().height * zoom()">
        <div class="relative origin-top-left" [style.width.px]="layout().width" [style.height.px]="layout().height"
             [style.transform]="'scale(' + zoom() + ')'">
          <svg class="pointer-events-none absolute inset-0 overflow-visible" [attr.width]="layout().width"
               [attr.height]="layout().height" aria-hidden="true">
            <defs>
              <marker [attr.id]="marker" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M0 0 L8 4 L0 8z" fill="context-stroke" />
              </marker>
            </defs>
            @for (edge of edges(); track edge.connection.id) {
              <path [attr.d]="edge.path" fill="none" stroke-width="2.5"
                    [attr.stroke]="edge.state === 'active' ? '#10b981' : edge.state === 'inactive' ? '#cbd5e1' : '#94a3b8'"
                    [attr.stroke-dasharray]="edge.state === 'inactive' ? '6 5' : null"
                    [attr.marker-end]="'url(#' + marker + ')'" [attr.data-flow-state]="edge.state"
                    [attr.data-edge-id]="edge.connection.id" />
              @if (edge.label) {
                <text [attr.x]="edge.labelX" [attr.y]="edge.labelY - 8" fill="#64748b" font-size="10"
                      font-weight="600">{{ edge.label }}
                </text>
              }
            }
          </svg>
          @for (node of layout().nodes; track node.id) {
            <button type="button" class="graph-node" [attr.data-status]="record(node.id)?.status || 'UNRECORDED'"
                    [class.is-selected]="selectedId() === node.id" [style.left.px]="node.x" [style.top.px]="node.y"
                    [attr.aria-pressed]="selectedId() === node.id"
                    [attr.aria-label]="text(node.name || node.id) + ', ' + (record(node.id)?.status || 'No execution record')"
                    [attr.data-node-id]="node.id" (click)="nodeSelected.emit(node.id)">
              <span class="mb-2 flex items-center justify-between gap-2"><span
                class="node-icon">{{ node.type.includes('trigger') ? '↗' : node.type === 'if-condition' ? '⑂' : '◇' }}</span><span
                class="node-state">{{ record(node.id)?.status || 'NO RECORD' }}</span></span>
              <span class="block truncate text-sm font-semibold text-slate-900"
                    [title]="text(node.name || node.id)">{{ text(node.name || node.id) }}</span>
              <span class="mt-1 block truncate font-mono text-[10px] text-slate-500">{{ node.type }}</span>
            </button>
          }
        </div>
      </div>
    </div>
    <div class="flex flex-wrap gap-x-4 gap-y-2 border-t border-slate-200 px-4 py-3 text-[11px] text-slate-500">
      <span>● Pending</span><span class="text-amber-600">● Running</span><span class="text-emerald-600">● Succeeded / active flow</span><span
      class="text-rose-600">● Failed</span><span>┄ Skipped / inactive flow</span>
    </div>
  `,
  standalone: true,
  styles: [`
    :host {
      display: block;
      min-width: 0
    }

    .graph-viewport {
      height: 410px;
      overflow: auto;
      background-color: #f8fafc;
      background-image: radial-gradient(#cbd5e1 .8px, transparent .8px);
      background-size: 18px 18px
    }

    .graph-node {
      position: absolute;
      width: 180px;
      height: 120px;
      padding: 12px;
      text-align: left;
      background: white;
      border: 1px solid #cbd5e1;
      border-radius: 16px;
      box-shadow: 0 6px 16px #0f172a09
    }

    .graph-node:hover {
      border-color: #818cf8
    }

    .graph-node:focus-visible {
      outline: 3px solid #818cf8;
      outline-offset: 3px
    }

    .graph-node.is-selected {
      border: 2px solid #6366f1;
      box-shadow: 0 0 0 4px #e0e7ff
    }

    .node-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      border-radius: 9px;
      background: #eef2ff;
      color: #4f46e5
    }

    .node-state {
      font-size: 9px;
      font-weight: 700;
      color: #64748b;
      letter-spacing: .03em
    }

    [data-status=RUNNING] {
      border-color: #f59e0b
    }

    [data-status=RUNNING] .node-state {
      color: #b45309
    }

    [data-status=SUCCEEDED] .node-state {
      color: #047857
    }

    [data-status=FAILED] {
      border-color: #fda4af
    }

    [data-status=FAILED] .node-state {
      color: #be123c
    }

    [data-status=SKIPPED] {
      border-style: dashed;
      background: #f1f5f9
    }

    [data-status=SKIPPED] .node-icon {
      background: #e2e8f0;
      color: #64748b
    }
  `]
})
export class ExecutionGraphComponent {
  readonly graph = input.required<ExecutionGraph>();
  readonly records = input<NodeExecutionResponse[]>([]);
  readonly logs = input<ExecutionLogResponse[]>([]);
  readonly selectedId = input<string | null>(null);
  readonly nodeSelected = output<string>();
  protected readonly marker = `execution-flow-arrow-${++graphInstance}`;
  protected readonly zoom = signal(1);
  protected readonly percent = computed(() => Math.round(this.zoom() * 100));
  protected readonly text = inspectionText;
  private readonly viewport = viewChild<ElementRef<HTMLElement>>('viewport');
  private readonly destroyRef = inject(DestroyRef);
  private readonly byId = computed(() => new Map(this.records().map(node => [node.workflowNodeId, node])));
  protected readonly layout = computed(() => {
    const graph = this.graph();
    const ranks = new Map<string, number>();
    const incoming = new Map(graph.nodes.map(n => [n.id, 0]));
    for (const edge of graph.connections) incoming.set(edge.targetNodeId, (incoming.get(edge.targetNodeId) ?? 0) + 1);
    const queue = graph.nodes.filter(n => !incoming.get(n.id)).map(n => n.id);
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index];
      for (const edge of graph.connections.filter(e => e.sourceNodeId === id)) {
        ranks.set(edge.targetNodeId, Math.max(ranks.get(edge.targetNodeId) ?? 0, (ranks.get(id) ?? 0) + 1));
        incoming.set(edge.targetNodeId, (incoming.get(edge.targetNodeId) ?? 1) - 1);
        if (incoming.get(edge.targetNodeId) === 0) queue.push(edge.targetNodeId);
      }
    }
    const rows = new Map<number, number>();
    const nodes = graph.nodes.map((node, index) => {
      const rank = ranks.get(node.id) ?? (queue.includes(node.id) ? 0 : index);
      const row = rows.get(rank) ?? 0;
      rows.set(rank, row + 1);
      const position = node.position;
      return { ...node, x: position && Number.isFinite(position.x) ? position.x : rank * 260,
        y: position && Number.isFinite(position.y) ? position.y : row * 165 };
    });
    const minX = Math.min(0, ...nodes.map(n => n.x));
    const minY = Math.min(0, ...nodes.map(n => n.y));
    const placed = nodes.map(n => ({ ...n, x: n.x - minX + 36, y: n.y - minY + 36 }));
    return { nodes: placed, width: Math.max(380, ...placed.map(n => n.x + 216)), height: Math.max(300, ...placed.map(n => n.y + 156)) };
  });
  protected readonly edges = computed(() => {
    const positions = new Map(this.layout().nodes.map(n => [n.id, n]));
    return this.graph().connections.flatMap(connection => {
      const source = positions.get(connection.sourceNodeId), target = positions.get(connection.targetNodeId);
      if (!source || !target) return [];
      const handle = connection.sourceHandle === 'output-2' ? 'false' : connection.sourceHandle === 'output' && source.type === 'if-condition' ? 'true' : connection.sourceHandle.toLowerCase();
      const startX = source.x + 180, startY = source.y + (handle === 'false' ? 92 : 52);
      const endX = target.x, endY = target.y + 60, midX = (startX + endX) / 2;
      return [{ connection, state: this.edgeState(connection, source), path: `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}`,
        label: source.type === 'if-condition' ? handle.toUpperCase() : '', labelX: startX + 14, labelY: startY }];
    });
  });

  constructor() {
    effect(() => { this.graph(); this.fit(); });
    afterNextRender(() => {
      this.fit();
      if (typeof ResizeObserver !== 'undefined' && this.viewport()) {
        const observer = new ResizeObserver(() => this.fit());
        observer.observe(this.viewport()!.nativeElement);
        this.destroyRef.onDestroy(() => observer.disconnect());
      }
    });
  }

  protected record(id: string): NodeExecutionResponse | undefined { return this.byId().get(id); }
  protected fit(): void {
    const width = this.viewport()?.nativeElement.clientWidth;
    if (width) this.zoom.set(Math.max(0.2, Math.min(1, width / this.layout().width)));
  }
  protected zoomBy(change: number): void { this.zoom.update(z => Math.max(0.2, Math.min(2, z + change))); }
  private edgeState(edge: ExecutionGraphConnection, source: ExecutionGraphNode): 'active' | 'inactive' | 'unresolved' {
    const record = this.record(source.id);
    if (record?.status === 'SKIPPED') return 'inactive';
    if (record?.status !== 'SUCCEEDED') return 'unresolved';
    if (source.type !== 'if-condition') return 'active';
    const decision = [...this.logs()].reverse().find(log => log.nodeExecutionId === record.id && log.details && typeof log.details === 'object' && 'selectedBranch' in log.details);
    const branch = (decision?.details as { selectedBranch?: unknown } | undefined)?.selectedBranch;
    if (typeof branch !== 'string' || !['true', 'false'].includes(branch.toLowerCase())) return 'unresolved';
    const handle = edge.sourceHandle === 'output' ? 'true' : edge.sourceHandle === 'output-2' ? 'false' : edge.sourceHandle.toLowerCase();
    return handle === branch.toLowerCase() ? 'active' : 'inactive';
  }
}
