import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ArrowRight, CheckCircle2, FolderTree, Layers3, Search, Sparkles, Star, Workflow, LucideAngularModule, type LucideIconData } from 'lucide-angular';
import type { WorkflowConnection, WorkflowEditorState, WorkflowNode, WorkflowNodeType } from '../../core/models/workflow-editor.model';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';

interface TemplateEntry {
  id: string;
  name: string;
  description: string;
  category: string;
  tags: string[];
  popularity: string;
  icon: LucideIconData;
  state: WorkflowEditorState;
}

const makeNode = (id: string, type: WorkflowNodeType, x: number, y: number): WorkflowNode => {
  const descriptors: Record<WorkflowNodeType, { name: string; description: string; config: Record<string, string | number | boolean | undefined> }> = {
    'manual-trigger': { name: 'Manual Trigger', description: 'Starts when someone runs this workflow.', config: { triggerInfo: 'Manual', description: 'Run from the dashboard or a scheduler.' } },
    'schedule-trigger': { name: 'Schedule Trigger', description: 'Runs on a recurring cadence.', config: { frequency: 'Daily', schedule: 'Every 24 hours' } },
    'webhook-trigger': { name: 'Webhook Trigger', description: 'Receives event payloads from an external app.', config: { url: 'https://example.com/webhook', method: 'POST' } },
    'http-request': { name: 'HTTP Request', description: 'Calls an outbound API.', config: { method: 'POST', url: 'https://api.example.com/v1/endpoint', headers: '{}', body: '{}' } },
    'send-email': { name: 'Send Email', description: 'Delivers an email notification.', config: { recipient: 'ops@example.com', subject: 'Important update', message: 'This is a workflow notification.' } },
    'database-query': { name: 'Database Query', description: 'Reads or writes application data.', config: { query: 'SELECT * FROM events WHERE status = "open"', connection: 'Primary DB' } },
    'log-message': { name: 'Log Message', description: 'Sends a message to the execution log.', config: { message: 'Workflow step completed successfully.' } },
    'if-condition': { name: 'IF Condition', description: 'Branches based on a condition.', config: { field: 'status', operator: 'equals', value: 'active' } },
    switch: { name: 'Switch', description: 'Routes values to multiple branches.', config: { field: 'event_type', value: 'customer.created' } },
    merge: { name: 'Merge', description: 'Combines upstream branches.', config: { strategy: 'last-write-wins' } },
    'set-data': { name: 'Set Data', description: 'Stores a runtime value.', config: { key: 'customer_id', value: 'cust_123' } },
    'transform-data': { name: 'Transform Data', description: 'Transforms payload data.', config: { mapping: 'customer.name -> customer_name' } },
    'filter-data': { name: 'Filter Data', description: 'Keeps only matching records.', config: { field: 'is_active', operator: 'equals', value: 'true' } }
  };

  const descriptor = descriptors[type];

  return {
    id,
    type,
    name: descriptor.name,
    description: descriptor.description,
    position: { x, y },
    configuration: { ...descriptor.config }
  };
};

const buildTemplateState = (name: string, nodeLayout: Array<{ type: WorkflowNodeType; x: number; y: number; id: string }>, connections: Array<{ id: string; sourceNodeId: string; targetNodeId: string; sourceHandle?: string }>): WorkflowEditorState => {
  const nodes = nodeLayout.map((node) => makeNode(node.id, node.type, node.x, node.y));

  return {
    workflowId: `${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-template`,
    workflowName: name,
    nodes,
    connections: connections.map((connection) => ({
      id: connection.id,
      sourceNodeId: connection.sourceNodeId,
      sourceHandle: connection.sourceHandle ?? 'output',
      targetNodeId: connection.targetNodeId,
      targetHandle: 'input'
    } as WorkflowConnection)),
    selectedNodeId: nodes[0]?.id ?? null,
    selectedConnectionId: null,
    isDirty: false
  };
};

const templateSeed: TemplateEntry[] = [
  {
    id: 'customer-onboarding',
    name: 'Customer Onboarding',
    description: 'Send a welcome sequence and validate account readiness after signup.',
    category: 'Sales',
    tags: ['CRM', 'Email', 'Automation'],
    popularity: 'High',
    icon: Workflow,
    state: buildTemplateState('Customer Onboarding', [
      { id: 'trigger', type: 'webhook-trigger', x: 80, y: 140 },
      { id: 'if-ready', type: 'if-condition', x: 380, y: 120 },
      { id: 'send-email', type: 'send-email', x: 720, y: 80 },
      { id: 'log-ready', type: 'log-message', x: 720, y: 280 }
    ], [
      { id: 'conn-1', sourceNodeId: 'trigger', targetNodeId: 'if-ready' },
      { id: 'conn-2', sourceNodeId: 'if-ready', sourceHandle: 'true', targetNodeId: 'send-email' },
      { id: 'conn-3', sourceNodeId: 'if-ready', sourceHandle: 'false', targetNodeId: 'log-ready' }
    ])
  },
  {
    id: 'support-triage',
    name: 'Support Triage',
    description: 'Route incoming requests to the correct team based on priority and intent.',
    category: 'Support',
    tags: ['Triage', 'Routing', 'Inbox'],
    popularity: 'Popular',
    icon: Layers3,
    state: buildTemplateState('Support Triage', [
      { id: 'inbound', type: 'webhook-trigger', x: 80, y: 140 },
      { id: 'classify', type: 'if-condition', x: 360, y: 140 },
      { id: 'assign', type: 'set-data', x: 700, y: 80 },
      { id: 'notify', type: 'send-email', x: 700, y: 260 }
    ], [
      { id: 'conn-1', sourceNodeId: 'inbound', targetNodeId: 'classify' },
      { id: 'conn-2', sourceNodeId: 'classify', sourceHandle: 'true', targetNodeId: 'assign' },
      { id: 'conn-3', sourceNodeId: 'classify', sourceHandle: 'false', targetNodeId: 'notify' }
    ])
  },
  {
    id: 'monthly-report',
    name: 'Monthly Report Pack',
    description: 'Aggregate metrics from multiple datasources and email the summary to stakeholders.',
    category: 'Reporting',
    tags: ['Analytics', 'Notifications'],
    popularity: 'High',
    icon: FolderTree,
    state: buildTemplateState('Monthly Report Pack', [
      { id: 'trigger', type: 'schedule-trigger', x: 80, y: 140 },
      { id: 'fetch', type: 'database-query', x: 360, y: 120 },
      { id: 'transform', type: 'transform-data', x: 680, y: 120 },
      { id: 'email', type: 'send-email', x: 980, y: 120 }
    ], [
      { id: 'conn-1', sourceNodeId: 'trigger', targetNodeId: 'fetch' },
      { id: 'conn-2', sourceNodeId: 'fetch', targetNodeId: 'transform' },
      { id: 'conn-3', sourceNodeId: 'transform', targetNodeId: 'email' }
    ])
  },
  {
    id: 'ops-alerting',
    name: 'Ops Alerting',
    description: 'Watch system health and escalate incidents when thresholds are crossed.',
    category: 'Operations',
    tags: ['Monitoring', 'Alerts'],
    popularity: 'Popular',
    icon: Sparkles,
    state: buildTemplateState('Ops Alerting', [
      { id: 'schedule', type: 'schedule-trigger', x: 80, y: 180 },
      { id: 'check', type: 'http-request', x: 360, y: 180 },
      { id: 'branch', type: 'if-condition', x: 700, y: 180 },
      { id: 'alert', type: 'send-email', x: 1000, y: 80 },
      { id: 'log', type: 'log-message', x: 1000, y: 280 }
    ], [
      { id: 'conn-1', sourceNodeId: 'schedule', targetNodeId: 'check' },
      { id: 'conn-2', sourceNodeId: 'check', targetNodeId: 'branch' },
      { id: 'conn-3', sourceNodeId: 'branch', sourceHandle: 'true', targetNodeId: 'alert' },
      { id: 'conn-4', sourceNodeId: 'branch', sourceHandle: 'false', targetNodeId: 'log' }
    ])
  },
  {
    id: 'sync-customer-data',
    name: 'Sync Customer Data',
    description: 'Keep billing, CRM, and support records aligned in real time when changes occur.',
    category: 'Data',
    tags: ['Sync', 'API', 'CRM'],
    popularity: 'High',
    icon: CheckCircle2,
    state: buildTemplateState('Sync Customer Data', [
      { id: 'webhook', type: 'webhook-trigger', x: 80, y: 140 },
      { id: 'load', type: 'database-query', x: 360, y: 140 },
      { id: 'transform', type: 'transform-data', x: 700, y: 120 },
      { id: 'save', type: 'database-query', x: 1040, y: 120 }
    ], [
      { id: 'conn-1', sourceNodeId: 'webhook', targetNodeId: 'load' },
      { id: 'conn-2', sourceNodeId: 'load', targetNodeId: 'transform' },
      { id: 'conn-3', sourceNodeId: 'transform', targetNodeId: 'save' }
    ])
  },
  {
    id: 'lead-nurture',
    name: 'Lead Nurture',
    description: 'Qualify prospects and send personalized follow-ups based on scoring thresholds.',
    category: 'Marketing',
    tags: ['Sales', 'Email'],
    popularity: 'Growing',
    icon: Star,
    state: buildTemplateState('Lead Nurture', [
      { id: 'lead', type: 'webhook-trigger', x: 80, y: 140 },
      { id: 'score', type: 'if-condition', x: 340, y: 140 },
      { id: 'email', type: 'send-email', x: 740, y: 80 },
      { id: 'log', type: 'log-message', x: 740, y: 260 }
    ], [
      { id: 'conn-1', sourceNodeId: 'lead', targetNodeId: 'score' },
      { id: 'conn-2', sourceNodeId: 'score', sourceHandle: 'true', targetNodeId: 'email' },
      { id: 'conn-3', sourceNodeId: 'score', sourceHandle: 'false', targetNodeId: 'log' }
    ])
  }
];

const WORKFLOW_STORAGE_KEY = 'flowforge-workflow-states';

const cloneTemplateState = (template: TemplateEntry): WorkflowEditorState => {
  const nodeIdMap = new Map<string, string>();
  const state = template.state;

  const clonedNodes = state.nodes.map((node) => {
    const clonedId = `${node.id}-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`;
    nodeIdMap.set(node.id, clonedId);
    return { ...node, id: clonedId, position: { ...node.position } };
  });

  const clonedConnections = state.connections.map((connection, index) => ({
    ...connection,
    id: `conn-${Date.now()}-${index}-${Math.random().toString(16).slice(2, 6)}`,
    sourceNodeId: nodeIdMap.get(connection.sourceNodeId) ?? connection.sourceNodeId,
    targetNodeId: nodeIdMap.get(connection.targetNodeId) ?? connection.targetNodeId
  }));

  return {
    ...state,
    workflowId: `wf-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
    workflowName: template.name,
    nodes: clonedNodes,
    connections: clonedConnections,
    selectedNodeId: clonedNodes[0]?.id ?? null,
    selectedConnectionId: null,
    isDirty: true
  };
};

@Component({
  selector: 'app-templates-page',
  standalone: true,
  imports: [MainLayoutComponent, LucideAngularModule],
  template: `
    <app-main-layout [title]="'Templates'">
      <div class="space-y-6">
        <header class="flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6 xl:flex-row xl:items-center xl:justify-between">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">Reusable patterns</p>
            <h1 class="mt-2 text-2xl font-semibold tracking-tight text-slate-900">Templates Gallery</h1>
            <p class="mt-1 text-sm text-slate-600">Start from proven workflow blueprints and adapt them to your stack.</p>
          </div>
        </header>

        <section class="rounded-3xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <label class="flex w-full items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-500 xl:max-w-md">
              <lucide-icon [img]="searchIcon" class="h-4 w-4" />
              <input type="search" [value]="search()" class="w-full border-0 bg-transparent text-slate-700 outline-none placeholder:text-slate-400" placeholder="Search templates" (input)="updateSearch($event)" />
            </label>

            <div class="flex flex-wrap items-center gap-3">
              <label class="flex items-center gap-2 text-sm text-slate-600">
                <span>Category</span>
                <select class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-300" [value]="categoryFilter()" (change)="updateCategoryFilter($any($event.target).value)">
                  <option value="All">All</option>
                  <option value="Sales">Sales</option>
                  <option value="Support">Support</option>
                  <option value="Reporting">Reporting</option>
                  <option value="Operations">Operations</option>
                  <option value="Data">Data</option>
                  <option value="Marketing">Marketing</option>
                </select>
              </label>

              <label class="flex items-center gap-2 text-sm text-slate-600">
                <span>Sort</span>
                <select class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-300" [value]="sortBy()" (change)="updateSort($any($event.target).value)">
                  <option value="popular">Most popular</option>
                  <option value="name-asc">Name A–Z</option>
                  <option value="name-desc">Name Z–A</option>
                </select>
              </label>
            </div>
          </div>
        </section>

        @if (filteredTemplates().length === 0) {
          <div class="rounded-3xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center shadow-sm">
            <p class="text-lg font-semibold text-slate-900">No templates match your filter.</p>
            <p class="mt-2 text-sm text-slate-500">Try another search term or category filter.</p>
          </div>
        } @else {
          <div class="grid gap-4 lg:grid-cols-2 2xl:grid-cols-3">
            @for (template of filteredTemplates(); track template.id) {
              <article class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md">
                <div class="flex items-start justify-between gap-3">
                  <div class="flex items-center gap-3">
                    <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                      <lucide-icon [img]="template.icon" class="h-5 w-5" />
                    </div>
                    <div>
                      <h2 class="font-semibold text-slate-900">{{ template.name }}</h2>
                      <p class="text-xs text-slate-500">{{ template.category }}</p>
                    </div>
                  </div>

                  <span class="inline-flex rounded-full border border-indigo-100 bg-indigo-50 px-2 py-1 text-[10px] font-medium uppercase tracking-[0.12em] text-indigo-700">
                    {{ template.popularity }}
                  </span>
                </div>

                <p class="mt-4 text-sm leading-6 text-slate-600">{{ template.description }}</p>

                <div class="mt-4 flex flex-wrap gap-2">
                  @for (tag of template.tags; track tag) {
                    <span class="rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[11px] text-slate-600">{{ tag }}</span>
                  }
                </div>

                <div class="mt-5 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                  <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Preview</p>
                  <div class="mt-3 space-y-2">
                    @for (node of previewNodes(template); track node.id) {
                      <div class="flex items-center gap-2">
                        <span class="h-2.5 w-2.5 rounded-full bg-indigo-500"></span>
                        <span class="text-sm text-slate-700">{{ node.name }}</span>
                      </div>
                    }
                  </div>
                </div>

                <div class="mt-5 flex items-center justify-between gap-3">
                  <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50" (click)="previewTemplate(template.id)">Preview</button>
                  <button type="button" class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-indigo-500" (click)="useTemplate(template)">
                    Use Template
                    <lucide-icon [img]="arrowIcon" class="h-4 w-4" />
                  </button>
                </div>
              </article>
            }
          </div>
        }
      </div>

      @if (selectedTemplate(); as template) {
        <div class="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/35 p-4">
          <div class="w-full max-w-2xl rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">Template preview</p>
                <h3 class="mt-2 text-xl font-semibold text-slate-900">{{ template.name }}</h3>
              </div>
              <button type="button" class="text-xl text-slate-500" aria-label="Close preview" (click)="closePreview()">×</button>
            </div>

            <div class="mt-5 grid gap-4 md:grid-cols-[1.3fr_1fr]">
              <div class="rounded-2xl border border-slate-200 bg-slate-50 p-4">
                <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Flow structure</p>
                <div class="mt-4 space-y-3">
                  @for (node of previewNodes(template); track node.id) {
                    <div class="flex items-center gap-3 rounded-xl border border-slate-200 bg-white p-3">
                      <span class="flex h-8 w-8 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600">
                        <lucide-icon [img]="template.icon" class="h-4 w-4" />
                      </span>
                      <div>
                        <p class="text-sm font-medium text-slate-800">{{ node.name }}</p>
                        <p class="text-xs text-slate-500">{{ node.description }}</p>
                      </div>
                    </div>
                  }
                </div>
              </div>

              <div class="rounded-2xl border border-slate-200 bg-slate-50 p-4">
                <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Summary</p>
                <p class="mt-3 text-sm leading-6 text-slate-700">{{ template.description }}</p>
                <div class="mt-4 flex flex-wrap gap-2">
                  @for (tag of template.tags; track tag) {
                    <span class="rounded-full border border-slate-200 bg-white px-2.5 py-1 text-[11px] text-slate-600">{{ tag }}</span>
                  }
                </div>
              </div>
            </div>

            <div class="mt-6 flex justify-end gap-3">
              <button type="button" class="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50" (click)="closePreview()">Close</button>
              <button type="button" class="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-indigo-500" (click)="useTemplate(template)">Use Template</button>
            </div>
          </div>
        </div>
      }
    </app-main-layout>
  `,
  host: { class: 'block' }
})
export class TemplatesPageComponent {
  protected readonly router = inject(Router);
  protected readonly search = signal('');
  protected readonly categoryFilter = signal('All');
  protected readonly sortBy = signal<'popular' | 'name-asc' | 'name-desc'>('popular');
  protected readonly selectedTemplateId = signal<string | null>(null);

  protected readonly searchIcon = Search;
  protected readonly arrowIcon = ArrowRight;

  protected readonly templates = signal<TemplateEntry[]>(templateSeed);

  protected readonly filteredTemplates = computed(() => {
    const query = this.search().trim().toLowerCase();
    const category = this.categoryFilter();

    const filtered = this.templates().filter((template) => {
      const matchesText = !query || template.name.toLowerCase().includes(query) || template.description.toLowerCase().includes(query) || template.tags.some((tag) => tag.toLowerCase().includes(query));
      const matchesCategory = category === 'All' || template.category === category;
      return matchesText && matchesCategory;
    });

    return [...filtered].sort((left, right) => {
      switch (this.sortBy()) {
        case 'name-asc':
          return left.name.localeCompare(right.name);
        case 'name-desc':
          return right.name.localeCompare(left.name);
        case 'popular':
        default:
          return left.popularity === right.popularity ? left.name.localeCompare(right.name) : left.popularity.localeCompare(right.popularity);
      }
    });
  });

  protected readonly selectedTemplate = computed(() => this.templates().find((template) => template.id === this.selectedTemplateId()) ?? null);

  protected updateSearch(event: Event): void {
    const value = (event.target as HTMLInputElement | null)?.value ?? '';
    this.search.set(value);
  }

  protected updateCategoryFilter(value: string): void {
    this.categoryFilter.set(value || 'All');
  }

  protected updateSort(value: string): void {
    this.sortBy.set((value as 'popular' | 'name-asc' | 'name-desc') || 'popular');
  }

  protected previewNodes(template: TemplateEntry): WorkflowNode[] {
    return template.state.nodes.slice(0, 4);
  }

  protected previewTemplate(templateId: string): void {
    this.selectedTemplateId.set(templateId);
  }

  protected closePreview(): void {
    this.selectedTemplateId.set(null);
  }

  protected useTemplate(template: TemplateEntry): void {
    const workflowState = cloneTemplateState(template);
    this.persistWorkflowState(workflowState);
    this.selectedTemplateId.set(null);
    this.navigateToWorkflow(workflowState.workflowId);
  }

  protected navigateToWorkflow(workflowId: string): void {
    void this.router.navigateByUrl(`/workflows/${workflowId}/editor`);
  }

  protected persistWorkflowState(workflowState: WorkflowEditorState): void {
    const saved = this.readWorkflowStore();
    saved[workflowState.workflowId] = workflowState;
    localStorage.setItem(WORKFLOW_STORAGE_KEY, JSON.stringify(saved));
  }

  protected readWorkflowStore(): Record<string, WorkflowEditorState> {
    try {
      const value = localStorage.getItem(WORKFLOW_STORAGE_KEY);
      if (!value) {
        return {};
      }
      const parsed = JSON.parse(value) as Record<string, WorkflowEditorState>;
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch {
      return {};
    }
  }
}
