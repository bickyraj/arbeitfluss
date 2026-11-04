import { Component, computed, signal } from '@angular/core';
import { Activity, AlertTriangle, ArrowRightLeft, Ban, CheckCircle2, ChevronLeft, ChevronRight, Clock3, Database, Download, Filter, Gauge, LucideAngularModule, RefreshCw, Search, ShieldAlert, XCircle } from 'lucide-angular';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';

export type ExecutionStatus = 'Succeeded' | 'Failed' | 'Running' | 'Cancelled';
export type ExecutionSortOption = 'newest' | 'oldest' | 'duration-desc' | 'status';

type ExecutionNodeStatus = 'Succeeded' | 'Failed' | 'Running' | 'Skipped' | 'Queued' | 'Cancelled';

interface ExecutionNodeLog {
  id: string;
  name: string;
  status: ExecutionNodeStatus;
  startedAt: string;
  duration: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
}

interface ExecutionRecord {
  id: string;
  workflowId: string;
  workflowName: string;
  status: ExecutionStatus;
  startedAt: string;
  finishedAt: string;
  duration: string;
  triggeredBy: string;
  triggerType: string;
  mode: string;
  summary: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  retryCount: number;
  nodes: ExecutionNodeLog[];
}

const executionSeed: ExecutionRecord[] = [
  {
    id: 'exec-1048',
    workflowId: 'wf-1',
    workflowName: 'Daily Report Generator',
    status: 'Succeeded',
    startedAt: '2026-11-04 09:18',
    finishedAt: '2026-11-04 09:20',
    duration: '2m 14s',
    triggeredBy: 'Ariana Reed',
    triggerType: 'Schedule',
    mode: 'Nightly',
    summary: 'Generated report bundle and synced latest campaign metrics.',
    input: { date: '2026-11-04', source: 'CRM', segments: ['Enterprise', 'Mid-market'] },
    output: { reportId: 'rpt-1092', status: 'published', recipients: 14 },
    retryCount: 0,
    nodes: [
      { id: 'n-1', name: 'Manual Trigger', status: 'Succeeded', startedAt: '09:18', duration: '20s', input: { source: 'scheduler' }, output: { trigger: 'manual' } },
      { id: 'n-2', name: 'Fetch Metrics', status: 'Succeeded', startedAt: '09:18', duration: '42s', input: { metric: 'revenue' }, output: { rows: 821 } },
      { id: 'n-3', name: 'Transform Data', status: 'Succeeded', startedAt: '09:19', duration: '31s', input: { dataset: 'sales' }, output: { normalized: true } },
      { id: 'n-4', name: 'Send Email', status: 'Succeeded', startedAt: '09:20', duration: '18s', input: { recipient: 'ops@example.com' }, output: { delivered: true } }
    ]
  },
  {
    id: 'exec-1047',
    workflowId: 'wf-4',
    workflowName: 'Finance Reconciliation',
    status: 'Failed',
    startedAt: '2026-11-04 08:47',
    finishedAt: '2026-11-04 08:49',
    duration: '1m 38s',
    triggeredBy: 'Caleb Morris',
    triggerType: 'Manual',
    mode: 'Ad hoc',
    summary: 'Transaction matching failed on the invoice export validation step.',
    input: { account: 'glass-440', period: '2026-10' },
    output: { error: 'invoice batch mismatch', failedNode: 'Validate Ledger' },
    retryCount: 1,
    nodes: [
      { id: 'n-1', name: 'Manual Trigger', status: 'Succeeded', startedAt: '08:47', duration: '12s', input: {}, output: { triggered: true } },
      { id: 'n-2', name: 'Import Ledger', status: 'Succeeded', startedAt: '08:47', duration: '28s', input: {}, output: { rows: 1250 } },
      { id: 'n-3', name: 'Validate Ledger', status: 'Failed', startedAt: '08:48', duration: '34s', input: { check: 'balances' }, output: { error: 'Expected 42 rows, received 38.' } },
      { id: 'n-4', name: 'Retry Policy', status: 'Skipped', startedAt: '08:49', duration: '0s', input: {}, output: { skipped: true } }
    ]
  },
  {
    id: 'exec-1046',
    workflowId: 'wf-6',
    workflowName: 'Inventory Sync',
    status: 'Running',
    startedAt: '2026-11-04 08:24',
    finishedAt: '—',
    duration: '2m 09s',
    triggeredBy: 'Luis Ortega',
    triggerType: 'Schedule',
    mode: 'Every 30 minutes',
    summary: 'Warehouse stock updates are currently processing new storefront changes.',
    input: { warehouse: 'west-2', channel: 'storefront' },
    output: { status: 'in-progress', processed: 184 },
    retryCount: 0,
    nodes: [
      { id: 'n-1', name: 'Schedule Trigger', status: 'Succeeded', startedAt: '08:24', duration: '8s', input: { schedule: '30m' }, output: { trigger: 'fired' } },
      { id: 'n-2', name: 'Fetch Inventory', status: 'Running', startedAt: '08:25', duration: '1m 02s', input: { warehouse: 'west-2' }, output: { scanned: 184 } },
      { id: 'n-3', name: 'Sync Storefront', status: 'Queued', startedAt: '08:26', duration: '—', input: {}, output: {} },
      { id: 'n-4', name: 'Audit Log', status: 'Queued', startedAt: '08:26', duration: '—', input: {}, output: {} }
    ]
  },
  {
    id: 'exec-1045',
    workflowId: 'wf-3',
    workflowName: 'Marketing Campaign Manager',
    status: 'Succeeded',
    startedAt: '2026-11-04 07:15',
    finishedAt: '2026-11-04 07:17',
    duration: '2m 03s',
    triggeredBy: 'Nia Patel',
    triggerType: 'Webhook',
    mode: 'Campaign launch',
    summary: 'Segment targeting completed and email sequence was queued successfully.',
    input: { campaign: 'winter-retention', audience: 'high-value' },
    output: { enqueued: 1824, batches: 4 },
    retryCount: 0,
    nodes: [
      { id: 'n-1', name: 'Webhook Trigger', status: 'Succeeded', startedAt: '07:15', duration: '14s', input: { eventId: 'evt-929' }, output: { accepted: true } },
      { id: 'n-2', name: 'Build Segments', status: 'Succeeded', startedAt: '07:15', duration: '44s', input: {}, output: { segments: 11 } },
      { id: 'n-3', name: 'Send Email', status: 'Succeeded', startedAt: '07:16', duration: '36s', input: { template: 'retention' }, output: { queued: true } }
    ]
  },
  {
    id: 'exec-1044',
    workflowId: 'wf-7',
    workflowName: 'Email Notifications',
    status: 'Cancelled',
    startedAt: '2026-11-04 06:51',
    finishedAt: '2026-11-04 06:52',
    duration: '44s',
    triggeredBy: 'Ariana Reed',
    triggerType: 'Manual',
    mode: 'Support alert',
    summary: 'Customer event notifications were cancelled before the outbound message dispatch.',
    input: { event: 'account.created' },
    output: { status: 'cancelled', reason: 'operator override' },
    retryCount: 0,
    nodes: [
      { id: 'n-1', name: 'Manual Trigger', status: 'Succeeded', startedAt: '06:51', duration: '9s', input: {}, output: { triggered: true } },
      { id: 'n-2', name: 'Resolve Recipients', status: 'Succeeded', startedAt: '06:51', duration: '20s', input: {}, output: { recipients: 12 } },
      { id: 'n-3', name: 'Send Email', status: 'Cancelled', startedAt: '06:52', duration: '0s', input: {}, output: { cancelled: true } }
    ]
  },
  {
    id: 'exec-1043',
    workflowId: 'wf-2',
    workflowName: 'Customer Data Sync',
    status: 'Succeeded',
    startedAt: '2026-11-04 05:12',
    finishedAt: '2026-11-04 05:14',
    duration: '1m 51s',
    triggeredBy: 'Marcus Chen',
    triggerType: 'Webhook',
    mode: 'Realtime',
    summary: 'Customer records were synchronized with the billing and CRM systems.',
    input: { accountId: 'acct_234', sync: 'full' },
    output: { upserted: 39, updated: 27 },
    retryCount: 1,
    nodes: [
      { id: 'n-1', name: 'Webhook Trigger', status: 'Succeeded', startedAt: '05:12', duration: '11s', input: { accountId: 'acct_234' }, output: { accepted: true } },
      { id: 'n-2', name: 'Load Account', status: 'Succeeded', startedAt: '05:12', duration: '24s', input: {}, output: { found: true } },
      { id: 'n-3', name: 'Sync CRMs', status: 'Succeeded', startedAt: '05:13', duration: '52s', input: { source: 'billing' }, output: { synced: true } }
    ]
  },
  {
    id: 'exec-1042',
    workflowId: 'wf-8',
    workflowName: 'CRM Integration',
    status: 'Failed',
    startedAt: '2026-11-03 22:40',
    finishedAt: '2026-11-03 22:43',
    duration: '3m 12s',
    triggeredBy: 'Ethan Brooks',
    triggerType: 'Manual',
    mode: 'Data refresh',
    summary: 'CRM sync aborted after a timeout while fetching the opportunity delta.',
    input: { since: '2026-11-02T00:00:00Z' },
    output: { error: 'request timeout', node: 'Fetch Opportunity Delta' },
    retryCount: 2,
    nodes: [
      { id: 'n-1', name: 'Manual Trigger', status: 'Succeeded', startedAt: '22:40', duration: '8s', input: {}, output: { trigger: 'successful' } },
      { id: 'n-2', name: 'Fetch Opportunity Delta', status: 'Failed', startedAt: '22:41', duration: '2m 03s', input: { endpoint: '/opportunities/delta' }, output: { error: '504 Gateway Timeout' } }
    ]
  }
];

@Component({
  selector: 'app-executions-page',
  standalone: true,
  imports: [MainLayoutComponent, LucideAngularModule],
  template: `
    <app-main-layout [title]="'Executions'">
      <div class="space-y-6">
        <header class="flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6 xl:flex-row xl:items-center xl:justify-between">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">Observability</p>
            <h1 class="mt-2 text-2xl font-semibold tracking-tight text-slate-900">Execution History</h1>
            <p class="mt-1 text-sm text-slate-600">Monitor workflow runs, diagnose failures, and inspect execution payloads.</p>
          </div>

          <div class="flex flex-wrap items-center gap-2">
            <button type="button" class="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50" (click)="refreshExecutions()">
              <lucide-icon [img]="refreshIcon" class="h-4 w-4" />
              Refresh
            </button>
            <button type="button" class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-indigo-500" (click)="retrySelectedExecution()">
              <lucide-icon [img]="refreshIcon" class="h-4 w-4" />
              Retry selected
            </button>
          </div>
        </header>

        <section class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          @for (stat of stats(); track stat.label) {
            <article class="rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_10px_30px_rgba(15,23,42,0.04)]">
              <div class="flex items-center justify-between gap-3">
                <div>
                  <p class="text-sm text-slate-500">{{ stat.label }}</p>
                  <p class="mt-3 text-3xl font-semibold tracking-tight text-slate-900">{{ stat.value }}</p>
                </div>
                <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                  <lucide-icon [img]="stat.icon" class="h-5 w-5" />
                </div>
              </div>
            </article>
          }
        </section>

        <section class="rounded-3xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <label class="flex w-full items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-500 xl:max-w-md">
              <lucide-icon [img]="searchIcon" class="h-4 w-4" />
              <input type="search" [value]="search()" class="w-full border-0 bg-transparent text-slate-700 outline-none placeholder:text-slate-400" placeholder="Search executions" (input)="updateSearch($event)" />
            </label>

            <div class="flex flex-wrap items-center gap-3">
              <label class="flex items-center gap-2 text-sm text-slate-600">
                <span>Status</span>
                <select class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-300" [value]="statusFilter()" (change)="updateStatusFilter($any($event.target).value)">
                  <option value="All">All</option>
                  <option value="Succeeded">Succeeded</option>
                  <option value="Failed">Failed</option>
                  <option value="Running">Running</option>
                  <option value="Cancelled">Cancelled</option>
                </select>
              </label>

              <label class="flex items-center gap-2 text-sm text-slate-600">
                <span>Sort</span>
                <select class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-300" [value]="sortBy()" (change)="updateSort($any($event.target).value)">
                  <option value="newest">Newest first</option>
                  <option value="oldest">Oldest first</option>
                  <option value="duration-desc">Longest duration</option>
                  <option value="status">Status</option>
                </select>
              </label>
            </div>
          </div>
        </section>

        <div class="grid gap-6 xl:grid-cols-[1.7fr_1fr]">
          <section class="rounded-3xl border border-slate-200 bg-white shadow-sm">
            <div class="overflow-x-auto">
              <table class="min-w-full table-auto text-left text-sm">
                <thead class="bg-slate-50 text-slate-600">
                  <tr>
                    <th class="px-5 py-3 font-medium">Workflow</th>
                    <th class="px-5 py-3 font-medium">Status</th>
                    <th class="px-5 py-3 font-medium">Started</th>
                    <th class="px-5 py-3 font-medium">Duration</th>
                    <th class="px-5 py-3 font-medium">Triggered by</th>
                    <th class="px-5 py-3 text-right font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-slate-200 text-slate-700">
                  @for (execution of paginatedExecutions(); track execution.id) {
                    <tr class="cursor-pointer transition hover:bg-slate-50" [class.bg-indigo-50]="selectedExecutionId() === execution.id" (click)="selectExecution(execution.id)">
                      <td class="px-5 py-4">
                        <div>
                          <p class="font-medium text-slate-900">{{ execution.workflowName }}</p>
                          <p class="text-xs text-slate-500">{{ execution.id }}</p>
                        </div>
                      </td>
                      <td class="px-5 py-4">
                        <span class="inline-flex rounded-full px-2.5 py-1 text-xs font-medium" [class.bg-emerald-50]="execution.status === 'Succeeded'" [class.text-emerald-700]="execution.status === 'Succeeded'" [class.bg-rose-50]="execution.status === 'Failed'" [class.text-rose-700]="execution.status === 'Failed'" [class.bg-amber-50]="execution.status === 'Running'" [class.text-amber-700]="execution.status === 'Running'" [class.bg-slate-200]="execution.status === 'Cancelled'" [class.text-slate-700]="execution.status === 'Cancelled'">
                          {{ execution.status }}
                        </span>
                      </td>
                      <td class="px-5 py-4">{{ execution.startedAt }}</td>
                      <td class="px-5 py-4">{{ execution.duration }}</td>
                      <td class="px-5 py-4">{{ execution.triggeredBy }}</td>
                      <td class="px-5 py-4">
                        <div class="flex justify-end gap-2">
                          <button type="button" class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100" (click)="$event.stopPropagation(); retryExecution(execution.id)">Retry</button>
                          <button type="button" class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100" (click)="$event.stopPropagation(); cancelExecution(execution.id)">Cancel</button>
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>

          <aside class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
            @if (selectedExecution(); as execution) {
              <div class="space-y-5">
                <div class="flex items-start justify-between gap-3">
                  <div>
                    <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Run details</p>
                    <h2 class="mt-2 text-xl font-semibold text-slate-900">{{ execution.workflowName }}</h2>
                    <p class="mt-1 text-sm text-slate-500">{{ execution.id }}</p>
                  </div>
                  <span class="inline-flex rounded-full px-2.5 py-1 text-xs font-medium" [class.bg-emerald-50]="execution.status === 'Succeeded'" [class.text-emerald-700]="execution.status === 'Succeeded'" [class.bg-rose-50]="execution.status === 'Failed'" [class.text-rose-700]="execution.status === 'Failed'" [class.bg-amber-50]="execution.status === 'Running'" [class.text-amber-700]="execution.status === 'Running'" [class.bg-slate-200]="execution.status === 'Cancelled'" [class.text-slate-700]="execution.status === 'Cancelled'">
                    {{ execution.status }}
                  </span>
                </div>

                <div class="grid gap-3 sm:grid-cols-2">
                  <div class="rounded-2xl border border-slate-200 bg-slate-50 p-3">
                    <p class="text-[11px] uppercase tracking-[0.12em] text-slate-500">Started</p>
                    <p class="mt-2 text-sm font-medium text-slate-800">{{ execution.startedAt }}</p>
                  </div>
                  <div class="rounded-2xl border border-slate-200 bg-slate-50 p-3">
                    <p class="text-[11px] uppercase tracking-[0.12em] text-slate-500">Duration</p>
                    <p class="mt-2 text-sm font-medium text-slate-800">{{ execution.duration }}</p>
                  </div>
                  <div class="rounded-2xl border border-slate-200 bg-slate-50 p-3">
                    <p class="text-[11px] uppercase tracking-[0.12em] text-slate-500">Triggered by</p>
                    <p class="mt-2 text-sm font-medium text-slate-800">{{ execution.triggeredBy }}</p>
                  </div>
                  <div class="rounded-2xl border border-slate-200 bg-slate-50 p-3">
                    <p class="text-[11px] uppercase tracking-[0.12em] text-slate-500">Retries</p>
                    <p class="mt-2 text-sm font-medium text-slate-800">{{ execution.retryCount }}</p>
                  </div>
                </div>

                <div class="flex flex-wrap gap-2">
                  <button type="button" class="rounded-xl bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-500" (click)="retryExecution(execution.id)">Retry</button>
                  <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50" (click)="cancelExecution(execution.id)">Cancel</button>
                </div>

                <div>
                  <p class="mb-2 text-xs font-medium uppercase tracking-[0.14em] text-slate-500">Summary</p>
                  <p class="rounded-2xl border border-slate-200 bg-slate-50 p-3 text-sm text-slate-700">{{ execution.summary }}</p>
                </div>

                <div>
                  <p class="mb-3 text-xs font-medium uppercase tracking-[0.14em] text-slate-500">Node timeline</p>
                  <div class="space-y-3">
                    @for (node of execution.nodes; track node.id) {
                      <div class="rounded-2xl border border-slate-200 bg-white p-3">
                        <div class="flex items-center justify-between gap-3">
                          <div class="flex min-w-0 items-center gap-2">
                            <span class="flex h-8 w-8 items-center justify-center rounded-lg" [class.bg-emerald-50]="node.status === 'Succeeded'" [class.text-emerald-700]="node.status === 'Succeeded'" [class.bg-rose-50]="node.status === 'Failed'" [class.text-rose-700]="node.status === 'Failed'" [class.bg-amber-50]="node.status === 'Running'" [class.text-amber-700]="node.status === 'Running'" [class.bg-slate-100]="node.status === 'Skipped' || node.status === 'Queued'" [class.text-slate-600]="node.status === 'Skipped' || node.status === 'Queued'">
                              @if (node.status === 'Succeeded') {<lucide-icon [img]="successIcon" class="h-4 w-4" />} @else if (node.status === 'Failed') {<lucide-icon [img]="errorIcon" class="h-4 w-4" />} @else if (node.status === 'Running') {<lucide-icon [img]="activityIcon" class="h-4 w-4" />} @else {<lucide-icon [img]="clockIcon" class="h-4 w-4" />} 
                            </span>
                            <div class="min-w-0">
                              <p class="truncate text-sm font-medium text-slate-800">{{ node.name }}</p>
                              <p class="text-[11px] text-slate-500">{{ node.startedAt }} • {{ node.duration }}</p>
                            </div>
                          </div>
                          <span class="text-[11px] font-medium uppercase tracking-[0.1em] text-slate-500">{{ node.status }}</span>
                        </div>
                      </div>
                    }
                  </div>
                </div>

                <div class="space-y-4">
                  <div>
                    <p class="mb-2 text-xs font-medium uppercase tracking-[0.14em] text-slate-500">Input payload</p>
                    <pre class="max-h-52 overflow-auto rounded-2xl border border-slate-200 bg-slate-950 p-3 text-xs text-slate-100">{{ formatJson(execution.input) }}</pre>
                  </div>
                  <div>
                    <p class="mb-2 text-xs font-medium uppercase tracking-[0.14em] text-slate-500">Output payload</p>
                    <pre class="max-h-52 overflow-auto rounded-2xl border border-slate-200 bg-slate-950 p-3 text-xs text-slate-100">{{ formatJson(execution.output) }}</pre>
                  </div>
                </div>
              </div>
            } @else {
              <div class="rounded-3xl border border-dashed border-slate-300 bg-slate-50 p-6 text-center">
                <p class="text-lg font-semibold text-slate-900">No execution selected</p>
                <p class="mt-2 text-sm text-slate-600">Choose a run to inspect its timeline and payloads.</p>
              </div>
            }
          </aside>
        </div>

        <div class="flex flex-col gap-3 rounded-3xl border border-slate-200 bg-white p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between">
          <p class="text-sm text-slate-600">Showing {{ paginatedExecutions().length }} of {{ filteredExecutions().length }} executions</p>

          <div class="flex items-center gap-3">
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50" [disabled]="currentPage() === 1" (click)="previousPage()">
              Previous
            </button>
            <span class="text-sm font-medium text-slate-700">Page {{ currentPage() }} of {{ pageCount() }}</span>
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50" [disabled]="currentPage() >= pageCount()" (click)="nextPage()">
              Next
            </button>
          </div>
        </div>
      </div>
    </app-main-layout>
  `,
  host: { class: 'block' }
})
export class ExecutionsPageComponent {
  protected readonly executions = signal<ExecutionRecord[]>(executionSeed);
  protected readonly search = signal('');
  protected readonly statusFilter = signal<'All' | ExecutionStatus>('All');
  protected readonly sortBy = signal<ExecutionSortOption>('newest');
  protected readonly currentPage = signal(1);
  protected readonly selectedExecutionId = signal<string>(executionSeed[0].id);

  protected readonly searchIcon = Search;
  protected readonly refreshIcon = RefreshCw;
  protected readonly successIcon = CheckCircle2;
  protected readonly errorIcon = AlertTriangle;
  protected readonly activityIcon = Activity;
  protected readonly clockIcon = Clock3;

  protected readonly pageSize = 5;

  protected readonly stats = computed(() => {
    const total = this.executions().length;
    const succeeded = this.executions().filter((execution) => execution.status === 'Succeeded').length;
    const failed = this.executions().filter((execution) => execution.status === 'Failed').length;
    const running = this.executions().filter((execution) => execution.status === 'Running').length;

    return [
      { label: 'Total runs', value: total.toString(), icon: Activity },
      { label: 'Succeeded', value: succeeded.toString(), icon: CheckCircle2 },
      { label: 'Failed', value: failed.toString(), icon: XCircle },
      { label: 'Running', value: running.toString(), icon: Gauge }
    ];
  });

  protected readonly filteredExecutions = computed(() => {
    const query = this.search().trim().toLowerCase();
    const status = this.statusFilter();

    const filtered = this.executions().filter((execution) => {
      const matchesText = !query || execution.workflowName.toLowerCase().includes(query) || execution.id.toLowerCase().includes(query) || execution.triggeredBy.toLowerCase().includes(query);
      const matchesStatus = status === 'All' || execution.status === status;
      return matchesText && matchesStatus;
    });

    return [...filtered].sort((left, right) => {
      switch (this.sortBy()) {
        case 'oldest':
          return new Date(left.startedAt).getTime() - new Date(right.startedAt).getTime();
        case 'duration-desc':
          return this.durationToMs(right.duration) - this.durationToMs(left.duration);
        case 'status':
          return left.status.localeCompare(right.status);
        case 'newest':
        default:
          return new Date(right.startedAt).getTime() - new Date(left.startedAt).getTime();
      }
    });
  });

  protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.filteredExecutions().length / this.pageSize)));

  protected readonly paginatedExecutions = computed(() => {
    const start = (this.currentPage() - 1) * this.pageSize;
    return this.filteredExecutions().slice(start, start + this.pageSize);
  });

  protected readonly selectedExecution = computed(() => {
    const selectedId = this.selectedExecutionId();
    const filtered = this.filteredExecutions();
    return filtered.find((execution) => execution.id === selectedId) ?? filtered[0] ?? null;
  });

  protected updateSearch(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    this.search.set(target?.value ?? '');
    this.currentPage.set(1);
  }

  protected updateStatusFilter(value: string): void {
    this.statusFilter.set((value as 'All' | ExecutionStatus) ?? 'All');
    this.currentPage.set(1);
  }

  protected updateSort(value: string): void {
    this.sortBy.set((value as ExecutionSortOption) ?? 'newest');
    this.currentPage.set(1);
  }

  protected previousPage(): void {
    this.currentPage.update((page) => Math.max(1, page - 1));
  }

  protected nextPage(): void {
    this.currentPage.update((page) => Math.min(this.pageCount(), page + 1));
  }

  protected selectExecution(executionId: string): void {
    this.selectedExecutionId.set(executionId);
  }

  protected retryExecution(executionId: string): void {
    this.executions.update((items) =>
      items.map((execution) => {
        if (execution.id !== executionId) {
          return execution;
        }

        return {
          ...execution,
          status: 'Running',
          retryCount: execution.retryCount + 1,
          startedAt: 'Just now',
          finishedAt: '—',
          duration: '0m 00s',
          summary: 'Retrying workflow from the last known successful checkpoint.',
          nodes: execution.nodes.map((node) => ({ ...node, status: node.status === 'Failed' ? 'Running' : node.status }))
        };
      })
    );
    this.selectedExecutionId.set(executionId);
  }

  protected cancelExecution(executionId: string): void {
    this.executions.update((items) =>
      items.map((execution) =>
        execution.id === executionId
          ? {
              ...execution,
              status: 'Cancelled',
              finishedAt: 'Just now',
              duration: execution.duration,
              summary: 'Execution cancelled by operator before the final handoff step.'
            }
          : execution
      )
    );
    this.selectedExecutionId.set(executionId);
  }

  protected refreshExecutions(): void {
    this.executions.update((items) => [...items]);
  }

  protected retrySelectedExecution(): void {
    if (this.selectedExecution()) {
      this.retryExecution(this.selectedExecution()!.id);
    }
  }

  protected formatJson(value: unknown): string {
    return JSON.stringify(value, null, 2);
  }

  protected durationToMs(value: string): number {
    const match = value.match(/(?:(\d+)h\s*)?(?:(\d+)m\s*)?(?:(\d+)s)?/i);
    if (!match) {
      return 0;
    }

    const hours = Number(match[1] ?? 0);
    const minutes = Number(match[2] ?? 0);
    const seconds = Number(match[3] ?? 0);
    return ((hours * 60 + minutes) * 60 + seconds) * 1000;
  }
}
