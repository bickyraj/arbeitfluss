import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ExecutionApiService } from '../../../core/services/execution-api.service';
import { ExecutionDetailsComponent } from '../../executions/execution-details';
import { environment } from '../../../../environments/environment';
import {
  ArrowLeft,
  CheckCircle2,
  Copy,
  Database,
  Download,
  Ellipsis,
  Filter,
  GitBranch,
  GitCommitHorizontal,
  Mail,
  RotateCcw,
  Redo,
  Save,
  Search,
  Send,
  Undo,
  Workflow,
  Zap,
  ZoomIn,
  ZoomOut,
  LucideAngularModule,
  type LucideIconData
} from 'lucide-angular';
import {
  WorkflowApiService,
  type WebhookRegistrationResponse
} from '../../../core/services/workflow-api.service';
import type {
  NodeLibraryItem,
  ConditionalOperator,
  WorkflowConnection,
  WorkflowEditorState,
  WorkflowNode,
  WorkflowNodeConfiguration,
  WorkflowConfigurationValue,
  WorkflowScalarValue,
  WorkflowNodeType
} from '../../../core/models/workflow-editor.model';

const NODE_LIBRARY: NodeLibraryItem[] = [
  { id: 'manual-trigger', name: 'Manual Trigger', description: 'Starts a workflow on demand.', category: 'Triggers' },
  { id: 'schedule-trigger', name: 'Schedule Trigger', description: 'Runs on a recurring schedule.', category: 'Triggers' },
  { id: 'webhook-trigger', name: 'Webhook Trigger', description: 'Receives payloads from external apps.', category: 'Triggers' },
  { id: 'http-request', name: 'HTTP Request', description: 'Calls an external API endpoint.', category: 'Actions' },
  { id: 'ai-model', name: 'AI Model', description: 'Generates text with a backend-configured provider.', category: 'Actions' },
  { id: 'send-email', name: 'Send Email', description: 'Dispatches an email to a recipient.', category: 'Actions' },
  { id: 'database-query', name: 'Database Query', description: 'Fetches or updates database records.', category: 'Actions' },
  { id: 'log-message', name: 'Log Message', description: 'Writes an execution log entry.', category: 'Actions' },
  { id: 'if-condition', name: 'IF / ELSE', description: 'Branches logic based on a condition.', category: 'Logic' },
  { id: 'switch', name: 'Switch', description: 'Routes based on multiple values.', category: 'Logic' },
  { id: 'merge', name: 'Merge', description: 'Combines multiple branches.', category: 'Logic' },
  { id: 'set-data', name: 'Set Data', description: 'Creates or updates workflow variables.', category: 'Data' },
  { id: 'transform-data', name: 'Transform Data', description: 'Maps or reshapes payload data.', category: 'Data' },
  { id: 'filter-data', name: 'Filter Data', description: 'Keeps only matching records.', category: 'Data' }
];

const makeNode = (id: string, type: WorkflowNodeType, x: number, y: number): WorkflowNode => {
  const descriptors: Record<WorkflowNodeType, { name: string; description: string; config: WorkflowNodeConfiguration }> = {
    'manual-trigger': {
      name: 'Manual Trigger',
      description: 'Starts when someone triggers this automation.',
      config: { triggerInfo: 'Manual', description: 'Run this workflow manually from the dashboard.' }
    },
    'schedule-trigger': {
      name: 'Schedule Trigger',
      description: 'Runs on a recurring time schedule.',
      config: { frequency: 'Daily', schedule: 'Every 24 hours' }
    },
    'webhook-trigger': {
      name: 'Webhook Trigger',
      description: 'Starts when an external application sends an HTTP request.',
      config: { method: 'POST', authentication: 'secret-token', active: true, responseMode: 'immediate' }
    },
    'http-request': {
      name: 'HTTP Request',
      description: 'Calls an external API endpoint.',
      config: {
        method: 'GET',
        url: 'https://api.example.com/v1/endpoint',
        responseFormat: 'auto',
        timeoutSeconds: 30,
        followRedirects: false,
        queryParameters: [],
        headers: [],
        bodyType: 'none',
        body: {}
      }
    },
    'ai-model': {
      name: 'AI Model',
      description: 'Generates text from workflow data using a backend-configured provider.',
      config: { provider: 'openai', model: 'gpt-4.1-mini', systemPrompt: '', userPrompt: 'Summarize: {{input}}', temperature: 0.7, maxOutputTokens: 512 }
    },
    'send-email': {
      name: 'Send Email',
      description: 'Sends a formatted email message.',
      config: { recipient: 'ops@example.com', subject: 'Workflow update', message: 'Hello team, here is the update.' }
    },
    'database-query': {
      name: 'Database Query',
      description: 'Loads or writes records from a database.',
      config: { query: 'SELECT * FROM events WHERE status = "open"', connection: 'Primary DB' }
    },
    'log-message': {
      name: 'Log Message',
      description: 'Writes messages to the execution log.',
      config: { message: 'Workflow step completed successfully.' }
    },
    'if-condition': {
      name: 'IF / ELSE',
      description: 'Branches when a condition is true.',
      config: { field: 'status', operator: 'equals', value: 'active' }
    },
    switch: {
      name: 'Switch',
      description: 'Routes to multiple branches based on a value.',
      config: { field: 'event_type', value: 'customer.created' }
    },
    merge: {
      name: 'Merge',
      description: 'Combines data streams into one route.',
      config: { strategy: 'last-write-wins' }
    },
    'set-data': {
      name: 'Set Data',
      description: 'Stores workflow variables for later steps.',
      config: { key: 'account_id', value: 'acct_123' }
    },
    'transform-data': {
      name: 'Transform Data',
      description: 'Maps fields and rewrites payloads.',
      config: { mapping: 'customer.name -> customer_name' }
    },
    'filter-data': {
      name: 'Filter Data',
      description: 'Drops items that do not match a condition.',
      config: { field: 'is_active', operator: 'equals', value: 'true' }
    }
  };

  const descriptor = descriptors[type];
  return {
    id,
    type,
    name: descriptor.name,
    description: descriptor.description,
    position: { x, y },
    configuration: descriptor.config
  };
};

const createInitialState = (): WorkflowEditorState => ({
  workflowId: 'wf-new',
  workflowName: 'Customer Onboarding Flow',
  nodes: [
    makeNode('node-1', 'manual-trigger', 120, 120),
    makeNode('node-2', 'if-condition', 420, 170),
    makeNode('node-3', 'send-email', 760, 120),
    makeNode('node-4', 'log-message', 760, 340)
  ],
  connections: [
    { id: 'conn-1', sourceNodeId: 'node-1', sourceHandle: 'output', targetNodeId: 'node-2', targetHandle: 'input' },
    { id: 'conn-2', sourceNodeId: 'node-2', sourceHandle: 'true', targetNodeId: 'node-3', targetHandle: 'input' },
    { id: 'conn-3', sourceNodeId: 'node-2', sourceHandle: 'false', targetNodeId: 'node-4', targetHandle: 'input' }
  ],
  selectedNodeId: 'node-1',
  selectedConnectionId: null,
  isDirty: false
});

const CLAMP = (value: number, min: number, max: number): number => Math.min(Math.max(value, min), max);
const CONDITION_OPERATORS: ConditionalOperator[] = [
  'equals',
  'not equals',
  'greater than',
  'greater than or equal',
  'less than',
  'less than or equal',
  'is empty',
  'is not empty'
];

interface HttpKeyValueEntry {
  [key: string]: WorkflowConfigurationValue | undefined;
  name: string;
  value: string;
  enabled: boolean;
}

@Component({
  selector: 'app-workflow-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LucideAngularModule, RouterLink, ExecutionDetailsComponent],
  template: `
    <div
      class="flex min-h-screen flex-col bg-[#F8FAFC] text-slate-900"
      (window:pointermove)="handleGlobalPointerMove($event)"
      (window:pointerup)="handleGlobalPointerUp($event)"
    >
      <header class="border-b border-slate-200 bg-white px-4 py-3 shadow-sm sm:px-5">
        <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
          <div class="flex items-center gap-3">
            <a routerLink="/workflows" class="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50">
              <lucide-icon [img]="backIcon" class="h-4 w-4" />
              Back to Workflows
            </a>
          <div class="min-w-0 flex-1">
            <input
              type="text"
              [value]="state().workflowName"
              class="min-w-0 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-base font-semibold text-slate-900 outline-none focus:border-indigo-400"
              (input)="updateWorkflowName($event)"
            />
            <textarea
              rows="2"
              [value]="state().workflowDescription ?? ''"
              class="mt-2 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600 outline-none focus:border-indigo-400"
              placeholder="Workflow description"
              (input)="updateWorkflowDescription($event)"
            ></textarea>
          </div>
          </div>

          <div class="flex flex-wrap items-center gap-2">
            <span class="inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700">
              <lucide-icon [img]="statusIcon" class="h-3.5 w-3.5" />
              {{ saveStateLabel() }}
            </span>
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50" [disabled]="!canUndo()" (click)="undo()">
              <lucide-icon [img]="undoIcon" class="h-4 w-4" />
            </button>
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50" [disabled]="!canRedo()" (click)="redo()">
              <lucide-icon [img]="redoIcon" class="h-4 w-4" />
            </button>
            <button type="button" aria-label="Zoom out" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50" (click)="zoomOut()">
              <lucide-icon [img]="zoomOutIcon" class="h-4 w-4" />
            </button>
            <button type="button" aria-label="Zoom in" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50" (click)="zoomIn()">
              <lucide-icon [img]="zoomInIcon" class="h-4 w-4" />
            </button>
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50" (click)="fitCanvas()">
              Fit canvas
            </button>
            @if (saveMessage()) {
              <span class="inline-flex items-center rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700">
                {{ saveMessage() }}
              </span>
            }
            @if (saveError()) {
              <span class="inline-flex items-center rounded-full border border-rose-200 bg-rose-50 px-2.5 py-1 text-xs font-medium text-rose-700">
                {{ saveError() }}
              </span>
            }
            <button type="button" class="rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-500 disabled:cursor-not-allowed disabled:bg-indigo-300" [disabled]="isLoading()" (click)="saveState()">
              {{ isLoading() ? 'Saving...' : 'Save' }}
            </button>
            <button type="button" class="rounded-xl bg-slate-900 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-slate-800" (click)="openExecutionDialog()">
              Run / Test
            </button>
          </div>
        </div>
      </header>

      <div class="flex min-h-0 flex-1 overflow-hidden">
        <aside class="w-full max-w-[280px] border-r border-slate-200 bg-white p-3 lg:w-[280px]">
          <div class="mb-3 flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2">
            <lucide-icon [img]="searchIcon" class="h-4 w-4 text-slate-500" />
            <input
              type="search"
              [value]="nodeSearch()"
              class="w-full border-0 bg-transparent text-sm text-slate-700 outline-none placeholder:text-slate-400"
              placeholder="Search nodes"
              (input)="updateNodeSearch($event)"
            />
          </div>

          <div class="max-h-[calc(100vh-180px)] space-y-5 overflow-y-auto pr-1">
            @for (category of categories(); track category) {
              <div>
                <p class="mb-2 px-2 text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">{{ category }}</p>
                <div class="space-y-2">
                  @for (node of nodeLibraryByCategory(category); track node.id; let i = $index) {
                    <button
                      type="button"
                      class="flex w-full items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3 text-left transition hover:border-indigo-200 hover:bg-indigo-50"
                      draggable="true"
                      (dragstart)="onLibraryDragStart($event, node.id)"
                      (click)="addNode(node.id, { x: 220, y: 150 + (i * 30) })"
                    >
                      <span class="flex h-9 w-9 items-center justify-center rounded-xl bg-white text-indigo-600 shadow-sm">
                        <lucide-icon [img]="getNodeIcon(node.id)" class="h-4 w-4" />
                      </span>
                      <span class="min-w-0 flex-1">
                        <span class="block text-sm font-medium text-slate-800">{{ node.name }}</span>
                        <span class="mt-0.5 block truncate text-[11px] text-slate-500">{{ node.description }}</span>
                      </span>
                    </button>
                  }
                </div>
              </div>
            }
          </div>
        </aside>

        <main class="relative flex min-w-0 flex-1 flex-col bg-[#F8FAFC]">
          <div
            #workflowCanvas
            class="relative min-h-[640px] flex-1 overflow-hidden"
            data-workflow-canvas
            [style.background-size]="'18px 18px'"
            [style.background-image]="'radial-gradient(circle, rgba(148, 163, 184, 0.28) 1.2px, transparent 0)'"
            (dragover)="allowCanvasDrop($event)"
            (drop)="onCanvasDrop($event)"
            (pointerdown)="startPanning($event)"
          >
            <div
              class="absolute left-0 top-0 h-full w-full"
              [style.transform]="'translate(' + panX() + 'px, ' + panY() + 'px) scale(' + zoom() + ')'"
              [style.transform-origin]="'0 0'"
            >
              @if (state().nodes.length === 0) {
                <div class="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-dashed border-slate-300 bg-white/80 px-6 py-5 text-center shadow-sm">
                  <p class="text-lg font-semibold text-slate-900">No nodes on this canvas</p>
                  <p class="mt-2 text-sm text-slate-500">Drag a node from the library or click one to add it.</p>
                </div>
              }

              <svg class="absolute inset-0 h-full w-full overflow-visible">
                <defs>
                  <marker id="connection-arrow-head" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto">
                    <path d="M 0 0 L 10 4 L 0 8 z" fill="currentColor" class="text-indigo-500"></path>
                  </marker>
                </defs>
                @for (connection of state().connections; track connection.id) {
                  <path
                    [attr.d]="buildConnectionPath(connection)"
                    class="fill-none stroke-slate-400 stroke-[2.5]"
                    [class.stroke-indigo-500]="selectedConnectionId() === connection.id"
                    [class.stroke-[3.5]]="selectedConnectionId() === connection.id"
                    marker-end="url(#connection-arrow-head)"
                    (click)="selectConnection(connection.id)"
                  ></path>
                }
                @if (pendingConnection()) {
                  <path [attr.d]="buildPendingConnectionPath()" class="fill-none stroke-indigo-500 stroke-[2.5] stroke-dasharray-6" marker-end="url(#connection-arrow-head)"></path>
                }
              </svg>

              @for (node of state().nodes; track node.id) {
                <div
                  [attr.data-workflow-node-id]="node.id"
                  class="absolute h-[120px] w-[180px] cursor-grab rounded-2xl border bg-white p-3 shadow-[0_14px_28px_rgba(15,23,42,0.08)]"
                  [style.left.px]="node.position.x"
                  [style.top.px]="node.position.y"
                  [class.border-indigo-500]="selectedNodeId() === node.id"
                  [class.border-slate-200]="selectedNodeId() !== node.id"
                  [class.shadow-indigo-100]="selectedNodeId() === node.id"
                  (pointerdown)="startNodeDrag($event, node.id)"
                  (click)="selectNode(node.id)"
                >
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <div class="flex items-center gap-2">
                      <span class="flex h-8 w-8 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                        <lucide-icon [img]="getNodeIcon(node.type)" class="h-4 w-4" />
                      </span>
                      <span class="text-sm font-semibold text-slate-900">{{ node.name }}</span>
                    </div>
                    <span class="inline-flex rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-medium text-slate-600">{{ node.type }}</span>
                  </div>

                  <p class="text-[11px] leading-4 text-slate-500">{{ node.description }}</p>
                  @if (node.type === 'if-condition') {
                    <p class="mt-1 truncate text-[10px] font-medium text-indigo-700">{{ conditionSummary(node) }}</p>
                  }

                  <button
                    type="button"
                    class="absolute -left-3 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full bg-white"
                    [attr.data-connection-node-id]="node.id"
                    data-connection-handle="input"
                    [attr.aria-label]="'Connect to ' + node.name"
                    (pointerdown)="stopHandlePointerDown($event)"
                  >
                    <span class="h-2.5 w-2.5 rounded-full border-2 border-slate-400 bg-white"></span>
                  </button>
                  @if (node.type === 'if-condition') {
                    <button
                      type="button"
                      class="absolute -right-3 top-1/3 flex h-6 w-6 items-center justify-center rounded-full bg-white"
                      [attr.data-connection-node-id]="node.id"
                      data-connection-handle="true"
                      [attr.aria-label]="'Start TRUE connection from ' + node.name"
                      (pointerdown)="beginConnection($event, node.id)"
                    >
                      <span class="h-2.5 w-2.5 rounded-full border-2 border-emerald-500 bg-emerald-50"></span>
                      <span class="absolute right-6 whitespace-nowrap text-[10px] font-semibold text-emerald-700">TRUE</span>
                    </button>
                    <button
                      type="button"
                      class="absolute -right-3 top-2/3 flex h-6 w-6 items-center justify-center rounded-full bg-white"
                      [attr.data-connection-node-id]="node.id"
                      data-connection-handle="false"
                      [attr.aria-label]="'Start FALSE connection from ' + node.name"
                      (pointerdown)="beginConnection($event, node.id)"
                    >
                      <span class="h-2.5 w-2.5 rounded-full border-2 border-rose-500 bg-rose-50"></span>
                      <span class="absolute right-6 whitespace-nowrap text-[10px] font-semibold text-rose-700">FALSE</span>
                    </button>
                  } @else {
                    <button
                      type="button"
                      class="absolute -right-3 top-1/2 flex h-6 w-6 items-center justify-center rounded-full bg-white"
                      [attr.data-connection-node-id]="node.id"
                      data-connection-handle="output"
                      [attr.aria-label]="'Start connection from ' + node.name"
                      (pointerdown)="beginConnection($event, node.id)"
                    >
                      <span class="h-2.5 w-2.5 rounded-full border-2 border-indigo-500 bg-indigo-50"></span>
                    </button>
                  }
                </div>
              }
            </div>
          </div>
        </main>

        <aside class="w-full max-w-[320px] border-l border-slate-200 bg-white p-4 lg:w-[320px]">
          @if (selectedNode(); as node) {
            <div class="space-y-5">
              <div>
                <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Configuration</p>
                <h3 class="mt-2 text-xl font-semibold text-slate-900">{{ node.name }}</h3>
              </div>

              <label class="block text-sm font-medium text-slate-700">
                Node name
                <input
                  type="text"
                  [value]="node.name"
                  class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400"
                  (input)="updateSelectedNodeField('name', $event)"
                />
              </label>

              <label class="block text-sm font-medium text-slate-700">
                Description
                <textarea
                  rows="3"
                  [value]="node.description"
                  class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400"
                  (input)="updateSelectedNodeField('description', $event)"
                ></textarea>
              </label>

              @switch (node.type) {
                @case ('manual-trigger') {
                  <div class="space-y-3">
                    <label class="block text-sm font-medium text-slate-700">Trigger info<input type="text" [value]="readConfig(node, 'triggerInfo')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('triggerInfo', $event)" /></label>
                    <label class="block text-sm font-medium text-slate-700">Description<input type="text" [value]="readConfig(node, 'description')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('description', $event)" /></label>
                  </div>
                }
                @case ('webhook-trigger') {
                  <div class="space-y-4">
                    <label class="block text-sm font-medium text-slate-700">HTTP method
                      <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'method') || 'POST'" (change)="updateSelectedNodeConfig('method', $event)">
                        <option>POST</option><option>GET</option><option>PUT</option><option>PATCH</option><option>DELETE</option>
                      </select>
                    </label>
                    <label class="block text-sm font-medium text-slate-700">Authentication
                      <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'authentication') || 'secret-token'" (change)="updateSelectedNodeConfig('authentication', $event)">
                        <option value="secret-token">Secret Token</option><option value="none">None</option>
                      </select>
                    </label>
                    <label class="flex items-center gap-2 text-sm text-slate-700">
                      <input type="checkbox" class="rounded border-slate-300 text-indigo-600" [checked]="readConfig(node, 'active') !== 'false'" (change)="updateWebhookActive($event)" />
                      Webhook node enabled
                    </label>
                    <div class="rounded-xl border border-slate-200 bg-slate-50 p-3">
                      <p class="text-xs font-semibold uppercase tracking-wide text-slate-500">Endpoint</p>
                      @if (webhookRegistration(node.id); as registration) {
                        <p class="mt-2 break-all font-mono text-xs text-slate-700">{{ webhookUrl(registration) }}</p>
                        <button type="button" class="mt-2 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100" (click)="copyWebhookUrl(registration)">Copy URL</button>
                        <p class="mt-3 text-xs font-medium" [class.text-emerald-700]="registration.enabled" [class.text-amber-700]="!registration.enabled">
                          {{ webhookStatus(node, registration) }}
                        </p>
                      } @else {
                        <p class="mt-2 text-xs text-slate-600">Save this workflow to provision its stable endpoint.</p>
                      }
                    </div>
                    @if (readConfig(node, 'authentication') !== 'none') {
                      <div class="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs leading-5 text-amber-900">
                        The secret is stored as a one-way hash and is shown only once after generation. Keep it private; it is not included in workflow responses.
                        @if (webhookRegistration(node.id); as registration) {
                          <button type="button" class="mt-3 block rounded-lg border border-amber-300 bg-white px-3 py-1.5 text-xs font-semibold text-amber-900 hover:bg-amber-100 disabled:opacity-50" [disabled]="!workflowId() || state().isDirty || webhookSecretBusy()" (click)="generateWebhookSecret(node.id)">
                            {{ webhookSecretBusy() ? 'Generating…' : registration.secretConfigured ? 'Regenerate Secret' : 'Generate Secret' }}
                          </button>
                        }
                      </div>
                    }
                    @if (webhookSecretReveal()?.nodeId === node.id) {
                      <div class="rounded-xl border border-indigo-200 bg-indigo-50 p-3">
                        <p class="text-xs font-semibold text-indigo-900">Copy and store this token now. It cannot be retrieved later.</p>
                        <code class="mt-2 block break-all text-xs text-indigo-950">{{ webhookSecretReveal()?.secret }}</code>
                        <button type="button" class="mt-2 mr-3 text-xs font-semibold text-indigo-700 underline" (click)="copyWebhookSecret()">Copy secret</button>
                        <button type="button" class="mt-2 text-xs font-semibold text-indigo-700 underline" (click)="dismissWebhookSecret()">Hide secret</button>
                      </div>
                    }
                    @if (webhookMessage()) {
                      <p role="status" class="text-xs text-slate-600">{{ webhookMessage() }}</p>
                    }
                    <div class="rounded-xl border border-slate-200 p-3">
                      <p class="text-xs font-semibold text-slate-700">Test with curl</p>
                      <pre class="mt-2 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-2 text-[11px] text-slate-100">curl -X {{ readConfig(node, 'method') || 'POST' }} "YOUR_WEBHOOK_URL" \\
  -H "Content-Type: application/json" \\
  -H "X-FlowForge-Webhook-Secret: YOUR_SECRET" \\
  -d '&#123;"orderId":"order_123","amount":99.99&#125;'</pre>
                      <p class="mt-2 text-[11px] leading-4 text-slate-500">Requests are acknowledged immediately and queued for asynchronous execution.</p>
                    </div>
                  </div>
                }
                @case ('http-request') {
                  <div class="space-y-4">
                    <label class="block text-sm font-medium text-slate-700">Method
                      <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'method')" (change)="updateHttpSetting('method', $event)">
                        <option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option>
                      </select>
                    </label>
                    <label class="block text-sm font-medium text-slate-700">URL
                      <input type="url" [value]="readConfig(node, 'url')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" placeholder="https://api.example.com/resource" (input)="updateHttpSetting('url', $event)" />
                    </label>
                    @if (httpUrlError(node)) { <p role="alert" class="text-xs text-rose-700">{{ httpUrlError(node) }}</p> }

                    <div class="grid grid-cols-2 gap-3">
                      <label class="block text-sm font-medium text-slate-700">Response format
                        <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'responseFormat')" (change)="updateHttpSetting('responseFormat', $event)">
                          <option value="auto">Auto</option><option value="json">JSON</option><option value="text">Text</option>
                        </select>
                      </label>
                      <label class="block text-sm font-medium text-slate-700">Timeout (seconds)
                        <input type="number" min="1" max="3600" step="1" [value]="readConfig(node, 'timeoutSeconds')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateHttpSetting('timeoutSeconds', $event)" />
                      </label>
                    </div>
                    @if (httpTimeoutError(node)) { <p role="alert" class="text-xs text-rose-700">{{ httpTimeoutError(node) }}</p> }
                    <label class="flex items-center gap-2 text-sm text-slate-700">
                      <input type="checkbox" [checked]="node.configuration['followRedirects'] === true" (change)="updateHttpSetting('followRedirects', $event)" />
                      Follow redirects (each destination is validated)
                    </label>

                    <section class="space-y-2 border-t border-slate-100 pt-3">
                      <div class="flex items-center justify-between">
                        <h4 class="text-sm font-semibold text-slate-800">Query parameters</h4>
                        <button type="button" class="text-xs font-medium text-indigo-600 hover:text-indigo-800" (click)="addHttpEntry('queryParameters')">Add parameter</button>
                      </div>
                      @for (parameter of httpEntries(node, 'queryParameters'); track $index; let index = $index) {
                        <div class="grid grid-cols-[1fr_1fr_auto_auto] items-center gap-2">
                          <input aria-label="Query parameter name" [value]="parameter.name" placeholder="Name" class="min-w-0 rounded-lg border border-slate-200 px-2 py-2 text-xs" (input)="updateHttpEntry('queryParameters', index, 'name', $event)" />
                          <input aria-label="Query parameter value" [value]="parameter.value" placeholder="Value" class="min-w-0 rounded-lg border border-slate-200 px-2 py-2 text-xs" (input)="updateHttpEntry('queryParameters', index, 'value', $event)" />
                          <input type="checkbox" aria-label="Enable query parameter" [checked]="parameter.enabled" (change)="toggleHttpEntry('queryParameters', index, $event)" />
                          <button type="button" aria-label="Remove query parameter" class="px-1 text-rose-600" (click)="removeHttpEntry('queryParameters', index)">×</button>
                        </div>
                      }
                    </section>

                    <section class="space-y-2 border-t border-slate-100 pt-3">
                      <div class="flex items-center justify-between">
                        <h4 class="text-sm font-semibold text-slate-800">Headers</h4>
                        <button type="button" class="text-xs font-medium text-indigo-600 hover:text-indigo-800" (click)="addHttpEntry('headers')">Add header</button>
                      </div>
                      @for (header of httpEntries(node, 'headers'); track $index; let index = $index) {
                        <div class="grid grid-cols-[1fr_1fr_auto_auto] items-center gap-2">
                          <input aria-label="Header name" [value]="header.name" placeholder="Name" class="min-w-0 rounded-lg border border-slate-200 px-2 py-2 text-xs" (input)="updateHttpEntry('headers', index, 'name', $event)" />
                          <input aria-label="Header value" [value]="header.value" placeholder="Value" class="min-w-0 rounded-lg border border-slate-200 px-2 py-2 text-xs" (input)="updateHttpEntry('headers', index, 'value', $event)" />
                          <input type="checkbox" aria-label="Enable header" [checked]="header.enabled" (change)="toggleHttpEntry('headers', index, $event)" />
                          <button type="button" aria-label="Remove header" class="px-1 text-rose-600" (click)="removeHttpEntry('headers', index)">×</button>
                        </div>
                      }
                      <p class="text-xs leading-5 text-amber-700">Header values are stored with this workflow. Avoid entering long-lived credentials; a credentials manager is not available yet.</p>
                    </section>

                    <label class="block text-sm font-medium text-slate-700">Request body
                      <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'bodyType') || 'none'" (change)="updateHttpSetting('bodyType', $event)">
                        <option value="none">None</option><option value="json">JSON</option>
                      </select>
                    </label>
                    @if (readConfig(node, 'bodyType') === 'json') {
                      <label class="block text-sm font-medium text-slate-700">JSON body
                        <textarea rows="7" spellcheck="false" [value]="httpBodyText(node)" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-950 px-3 py-2.5 font-mono text-xs text-slate-100 outline-none focus:border-indigo-400" (input)="updateHttpBody($event)"></textarea>
                      </label>
                      @if (httpBodyError()) { <p role="alert" class="text-xs text-rose-700">{{ httpBodyError() }}</p> }
                    }
                  </div>
                }
                @case ('ai-model') {
                  <div class="space-y-3">
                    <p class="rounded-xl bg-indigo-50 p-3 text-xs leading-5 text-indigo-800">The OpenAI API key is configured on the backend. Do not enter credentials in prompts or workflow settings.</p>
                    <label class="block text-sm font-medium text-slate-700">Provider
                      <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" [value]="readConfig(node, 'provider') || 'openai'" (change)="updateSelectedNodeConfig('provider', $event)">
                        <option value="openai">OpenAI</option>
                      </select>
                    </label>
                    <label class="block text-sm font-medium text-slate-700">Model
                      <input type="text" maxlength="200" [value]="readConfig(node, 'model')" placeholder="gpt-4.1-mini" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" (input)="updateSelectedNodeConfig('model', $event)" />
                    </label>
                    <label class="block text-sm font-medium text-slate-700">System prompt
                      <textarea rows="4" maxlength="16000" [value]="readConfig(node, 'systemPrompt')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" (input)="updateSelectedNodeConfig('systemPrompt', $event)"></textarea>
                    </label>
                    <label class="block text-sm font-medium text-slate-700">User prompt
                      <textarea rows="6" maxlength="32000" [value]="readConfig(node, 'userPrompt')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" (input)="updateSelectedNodeConfig('userPrompt', $event)"></textarea>
                    </label>
                    <p class="text-xs leading-5 text-slate-500">Use <code>{{ '{{input}}' }}</code>, <code>{{ '{{input.field}}' }}</code>, or <code>{{ '{{upstream.node-id.field}}' }}</code> to reference execution data. Missing references fail the node.</p>
                    <div class="grid grid-cols-2 gap-3">
                      <label class="block text-sm font-medium text-slate-700">Temperature
                        <input type="number" min="0" max="2" step="0.1" [value]="readConfig(node, 'temperature')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" (input)="updateAiNumber('temperature', $event)" />
                      </label>
                      <label class="block text-sm font-medium text-slate-700">Maximum output tokens
                        <input type="number" min="1" max="8192" step="1" [value]="readConfig(node, 'maxOutputTokens')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm" (input)="updateAiNumber('maxOutputTokens', $event)" />
                      </label>
                    </div>
                  </div>
                }
                @case ('send-email') {
                  <div class="space-y-3">
                    <label class="block text-sm font-medium text-slate-700">Recipient<input type="text" [value]="readConfig(node, 'recipient')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('recipient', $event)" /></label>
                    <label class="block text-sm font-medium text-slate-700">Subject<input type="text" [value]="readConfig(node, 'subject')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('subject', $event)" /></label>
                    <label class="block text-sm font-medium text-slate-700">Message<textarea rows="4" [value]="readConfig(node, 'message')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('message', $event)"></textarea></label>
                  </div>
                }
                @case ('if-condition') {
                  <div class="space-y-3">
                    <p class="rounded-xl bg-indigo-50 px-3 py-2 text-sm text-indigo-800">
                      {{ conditionSummary(node) }}
                    </p>
                    <label class="block text-sm font-medium text-slate-700">Field<input type="text" [value]="readConfig(node, 'field')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('field', $event)" /></label>
                    <label class="block text-sm font-medium text-slate-700">Operator<select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'operator')" (change)="updateSelectedNodeConfig('operator', $event)">@for (operator of conditionOperators; track operator) { <option [value]="operator">{{ operator }}</option> }</select></label>
                    @if (conditionNeedsValue(node)) {
                      <label class="block text-sm font-medium text-slate-700">Value type
                        <select aria-label="Comparison value type" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="conditionValueType(node)" (change)="updateConditionValueType($event)">
                          <option value="string">Text</option>
                          <option value="number">Number</option>
                          <option value="boolean">Boolean</option>
                          <option value="null">Null</option>
                        </select>
                      </label>
                      @switch (conditionValueType(node)) {
                        @case ('number') {
                          <label class="block text-sm font-medium text-slate-700">Comparison Value<input aria-label="Comparison value" type="number" [value]="readConfig(node, 'value')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateConditionValue($event)" /></label>
                        }
                        @case ('boolean') {
                          <label class="block text-sm font-medium text-slate-700">Comparison Value<select aria-label="Comparison value" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'value')" (change)="updateConditionValue($event)"><option value="true">True</option><option value="false">False</option></select></label>
                        }
                        @case ('null') {
                          <p class="text-xs text-slate-500">The comparison value is JSON null.</p>
                        }
                        @default {
                          <label class="block text-sm font-medium text-slate-700">Comparison Value<input aria-label="Comparison value" type="text" [value]="readConfig(node, 'value')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateConditionValue($event)" /></label>
                        }
                      }
                    }
                  </div>
                }
                @default {
                  <div class="space-y-3">
                    @for (entry of configEntries(node); track entry[0]) {
                      <label class="block text-sm font-medium text-slate-700">
                        {{ entry[0] }}
                        <input
                          type="text"
                          [value]="configValueText(entry[1])"
                          class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400"
                          (input)="updateSelectedNodeConfig(entry[0], $event)"
                        />
                      </label>
                    }
                  </div>
                }
              }

              <div class="flex items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                <div>
                  <p class="text-xs uppercase tracking-[0.12em] text-slate-500">Node actions</p>
                  <p class="mt-1 text-sm text-slate-600">Selected node options</p>
                </div>
                <button type="button" class="rounded-xl bg-rose-50 px-3 py-2 text-sm font-medium text-rose-600 hover:bg-rose-100" (click)="deleteSelectedNode()">
                  Delete
                </button>
              </div>
            </div>
          } @else if (selectedConnectionId()) {
            <div class="space-y-4">
              <div>
                <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Connection</p>
                <h3 class="mt-2 text-xl font-semibold text-slate-900">Selected connection</h3>
              </div>
              <div class="rounded-2xl border border-slate-200 bg-slate-50 p-4 text-sm text-slate-600">
                This connection links two workflow nodes. Remove it to break the execution path.
              </div>
              <button type="button" class="w-full rounded-xl bg-rose-50 px-4 py-2.5 text-sm font-medium text-rose-600 hover:bg-rose-100" (click)="deleteSelectedConnection()">
                Delete connection
              </button>
            </div>
          } @else {
            <div class="rounded-3xl border border-dashed border-slate-300 bg-slate-50 p-5 text-center">
              <p class="text-lg font-semibold text-slate-900">No selection</p>
              <p class="mt-2 text-sm text-slate-600">Select a node or path to edit its settings.</p>
            </div>
          }
        </aside>
      </div>

      @if (executionDialogOpen()) {
        <div class="fixed inset-0 z-50 overflow-y-auto bg-slate-950/50 p-4" (click)="closeExecutionDialog()">
          <section
            role="dialog"
            aria-modal="true"
            aria-labelledby="execution-dialog-title"
            class="mx-auto my-6 max-h-[calc(100vh-3rem)] w-full max-w-3xl overflow-y-auto rounded-3xl bg-white p-5 shadow-2xl sm:p-6"
            (click)="$event.stopPropagation()"
          >
            <div class="flex items-start justify-between gap-4">
              <div>
                <h2 id="execution-dialog-title" class="text-xl font-semibold text-slate-900">Run workflow</h2>
                <p class="mt-1 text-sm text-slate-600">Provide JSON input for the Manual Trigger node.</p>
              </div>
              <button type="button" aria-label="Close run dialog" class="rounded-lg px-2 py-1 text-slate-500 hover:bg-slate-100" [disabled]="isStartingExecution()" (click)="closeExecutionDialog()">✕</button>
            </div>

            <div class="mt-5">
              <label for="execution-trigger-input" class="block text-sm font-medium text-slate-700">Trigger input (JSON)</label>
              <textarea
                id="execution-trigger-input"
                rows="8"
                spellcheck="false"
                [value]="executionInput()"
                [disabled]="isStartingExecution() || startedExecutionId() !== null"
                class="mt-2 w-full resize-y rounded-2xl border border-slate-300 bg-slate-950 px-4 py-3 font-mono text-sm text-slate-100 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 disabled:opacity-70"
                aria-describedby="execution-input-help execution-input-error"
                (input)="updateExecutionInput($event)"
              ></textarea>
              <p id="execution-input-help" class="mt-2 text-xs text-slate-500">The parsed JSON value is sent as <code>triggerInput</code> and becomes the Manual Trigger node's output.</p>
              @if (executionInputError()) {
                <p id="execution-input-error" role="alert" class="mt-2 text-sm text-rose-700">{{ executionInputError() }}</p>
              }
            </div>

            <div class="mt-4 max-w-xs">
              <label for="execution-timeout" class="block text-sm font-medium text-slate-700">Execution timeout (seconds)</label>
              <input
                id="execution-timeout"
                type="number"
                min="1"
                max="3600"
                step="1"
                [value]="executionTimeoutSeconds()"
                [disabled]="isStartingExecution() || startedExecutionId() !== null"
                class="mt-2 w-full rounded-xl border border-slate-300 px-3 py-2 text-sm text-slate-800 disabled:opacity-70"
                (input)="updateExecutionTimeout($event)"
              />
              <p class="mt-1 text-xs text-slate-500">Allowed range: 1–3600 seconds. Default: 300.</p>
              @if (executionTimeoutError()) {
                <p role="alert" class="mt-1 text-sm text-rose-700">{{ executionTimeoutError() }}</p>
              }
            </div>

            @if (!workflowId() || state().isDirty) {
              <p role="status" class="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
                {{ !workflowId() ? 'Save this workflow before running it.' : 'Save your changes before running. Execution uses the saved workflow graph.' }}
              </p>
            }
            <p class="mt-4 rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-600">
              Executable node types include Manual Trigger, Set Data, Log Message, IF / ELSE, and HTTP Request.
            </p>

            @if (executionStartError()) {
              <p role="alert" class="mt-4 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">{{ executionStartError() }}</p>
            }

            @if (startedExecutionId(); as executionId) {
              <div class="mt-5 space-y-4">
                <div class="flex flex-wrap items-center justify-between gap-3">
                  <p class="text-sm font-medium text-slate-700">Execution accepted. Status and node results update while it runs.</p>
                  <a
                    [routerLink]="['/executions']"
                    [queryParams]="{ executionId: executionId }"
                    class="rounded-xl border border-indigo-200 px-3 py-2 text-sm font-medium text-indigo-700 hover:bg-indigo-50"
                  >Open execution details</a>
                </div>
                <app-execution-details [executionId]="executionId" (retryStarted)="onExecutionRetryStarted($event)" />
              </div>
            } @else {
              <div class="mt-5 flex flex-wrap justify-end gap-3">
                <button type="button" class="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50" [disabled]="isStartingExecution()" (click)="closeExecutionDialog()">Cancel</button>
                <button
                  type="button"
                  class="rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:cursor-not-allowed disabled:opacity-50"
                  [disabled]="isStartingExecution() || !!executionInputError() || !!executionTimeoutError() || !workflowId() || state().isDirty || isLoading()"
                  (click)="startWorkflowExecution()"
                >{{ isStartingExecution() ? 'Starting…' : 'Execute workflow' }}</button>
              </div>
            }
          </section>
        </div>
      }
    </div>
  `
})
export class WorkflowEditorComponent {
  protected readonly router = inject(Router);
  protected readonly route = inject(ActivatedRoute);
  protected readonly workflowApi = inject(WorkflowApiService);
  protected readonly executionApi = inject(ExecutionApiService);
  private readonly workflowCanvas = viewChild.required<ElementRef<HTMLElement>>('workflowCanvas');
  protected readonly storageKey = 'flowforge-workflow-states';
  protected readonly history = signal<WorkflowEditorState[]>([]);
  protected readonly historyIndex = signal(0);
  protected readonly isLoading = signal(false);
  protected readonly saveMessage = signal<string | null>(null);
  protected readonly saveError = signal<string | null>(null);
  protected readonly workflowId = signal<string | null>(null);
  protected readonly workflowActive = signal(false);
  protected readonly webhookRegistrations = signal<WebhookRegistrationResponse[]>([]);
  protected readonly webhookSecretReveal = signal<{ nodeId: string; secret: string } | null>(null);
  protected readonly webhookSecretBusy = signal(false);
  protected readonly webhookMessage = signal<string | null>(null);
  protected readonly executionDialogOpen = signal(false);
  protected readonly executionInput = signal('{\n  "user": "Alex",\n  "message": "Hello FlowForge"\n}');
  protected readonly executionTimeoutSeconds = signal('300');
  protected readonly isStartingExecution = signal(false);
  protected readonly executionStartError = signal<string | null>(null);
  protected readonly startedExecutionId = signal<string | null>(null);
  protected readonly httpBodyError = signal<string | null>(null);
  protected readonly executionInputError = computed(() => {
    try {
      JSON.parse(this.executionInput());
      return null;
    } catch {
      return 'Enter valid JSON before starting the workflow.';
    }
  });
  protected readonly executionTimeoutError = computed(() => {
    const seconds = Number(this.executionTimeoutSeconds());
    return Number.isInteger(seconds) && seconds >= 1 && seconds <= 3600
      ? null
      : 'Execution timeout must be a whole number between 1 and 3600 seconds.';
  });

  constructor() {
    const routeId = this.route.snapshot.paramMap.get('id');
    this.workflowId.set(routeId);
    this.history.set([this.loadInitialState()]);
    if (routeId) {
      this.loadWorkflowFromApi(routeId);
    }
  }
  protected readonly state = computed(() => this.history()[this.historyIndex()]);
  protected readonly nodeSearch = signal('');
  protected readonly zoom = signal(1);
  protected readonly panX = signal(0);
  protected readonly panY = signal(0);
  protected readonly pendingConnection = signal<{ sourceNodeId: string; sourceHandle: string; x: number; y: number } | null>(null);
  protected readonly draggingNodeId = signal<string | null>(null);
  protected readonly dragOffset = signal({ x: 0, y: 0 });
  protected readonly panning = signal(false);
  protected readonly lastPointer = signal({ x: 0, y: 0 });

  private pointerToCanvas(event: PointerEvent): { x: number; y: number } {
    const canvas = this.workflowCanvas().nativeElement;
    const bounds = canvas.getBoundingClientRect();
    return {
      x: (event.clientX - bounds.left + canvas.scrollLeft - this.panX()) / this.zoom(),
      y: (event.clientY - bounds.top + canvas.scrollTop - this.panY()) / this.zoom()
    };
  }

  protected readonly backIcon = ArrowLeft;
  protected readonly searchIcon = Search;
  protected readonly statusIcon = CheckCircle2;
  protected readonly undoIcon = Undo;
  protected readonly redoIcon = Redo;
  protected readonly zoomOutIcon = ZoomOut;
  protected readonly zoomInIcon = ZoomIn;
  protected readonly saveIcon = Save;
  protected readonly workflowIcon = Workflow;
  protected readonly ellipsisIcon = Ellipsis;

  protected readonly categories = computed(() => ['Triggers', 'Actions', 'Logic', 'Data']);
  protected readonly filteredLibrary = computed(() => {
    const query = this.nodeSearch().trim().toLowerCase();
    return NODE_LIBRARY.filter((item) => !query || item.name.toLowerCase().includes(query) || item.description.toLowerCase().includes(query));
  });

  protected readonly selectedNode = computed(() => {
    const selectedId = this.state().selectedNodeId;
    return selectedId ? this.state().nodes.find((node) => node.id === selectedId) ?? null : null;
  });

  protected readonly selectedNodeId = computed(() => this.state().selectedNodeId);
  protected readonly selectedConnectionId = computed(() => this.state().selectedConnectionId);

  protected readonly canUndo = computed(() => this.historyIndex() > 0);
  protected readonly canRedo = computed(() => this.historyIndex() < this.history().length - 1);
  protected readonly saveStateLabel = computed(() => (this.state().isDirty ? 'Unsaved changes' : 'Saved'));
  protected readonly conditionOperators = CONDITION_OPERATORS;

  protected nodeLibraryByCategory(category: string): NodeLibraryItem[] {
    return this.filteredLibrary().filter((item) => item.category === category);
  }

  protected getNodeIcon(nodeType: WorkflowNodeType): LucideIconData {
    const mapping: Record<WorkflowNodeType, LucideIconData> = {
      'manual-trigger': Zap,
      'schedule-trigger': RotateCcw,
      'webhook-trigger': Send,
      'http-request': Download,
      'ai-model': Zap,
      'send-email': Mail,
      'database-query': Database,
      'log-message': GitCommitHorizontal,
      'if-condition': Filter,
      switch: GitBranch,
      merge: GitBranch,
      'set-data': Copy,
      'transform-data': Copy,
      'filter-data': Filter
    };
    return mapping[nodeType];
  }

  protected allowCanvasDrop(event: DragEvent): void {
    event.preventDefault();
  }

  protected onCanvasDrop(event: DragEvent): void {
    event.preventDefault();
    const nodeType = event.dataTransfer?.getData('text/plain') as WorkflowNodeType | undefined;
    if (!nodeType) {
      return;
    }

    const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const canvas = event.currentTarget as HTMLElement;
    const x = (event.clientX - bounds.left + canvas.scrollLeft - this.panX()) / this.zoom();
    const y = (event.clientY - bounds.top + canvas.scrollTop - this.panY()) / this.zoom();
    this.addNode(nodeType, { x: Math.max(0, x), y: Math.max(0, y) });
  }

  protected onLibraryDragStart(event: DragEvent, nodeType: WorkflowNodeType): void {
    event.dataTransfer?.setData('text/plain', nodeType);
  }

  protected updateNodeSearch(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    this.nodeSearch.set(target?.value ?? '');
  }

  protected updateWorkflowName(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    if (!target) {
      return;
    }

    this.pushState({ ...this.state(), workflowName: target.value, isDirty: true });
  }

  protected updateWorkflowDescription(event: Event): void {
    const target = event.target as HTMLTextAreaElement | null;
    if (!target) {
      return;
    }

    this.pushState({ ...this.state(), workflowDescription: target.value, isDirty: true });
  }

  protected updateSelectedNodeConfig(key: string, event: Event): void {
    const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement | null;
    const value = target?.value ?? '';
    const selectedId = this.selectedNodeId();
    if (!selectedId) {
      return;
    }

    this.pushState({
      ...this.state(),
      nodes: this.state().nodes.map((node) =>
        node.id === selectedId
          ? {
              ...node,
              configuration: {
                ...node.configuration,
                [key]: value
              }
            }
          : node
      ),
      isDirty: true
    });
  }

  protected updateWebhookActive(event: Event): void {
    const active = (event.target as HTMLInputElement | null)?.checked ?? false;
    const selectedId = this.selectedNodeId();
    if (!selectedId) return;
    this.pushState({
      ...this.state(),
      nodes: this.state().nodes.map((node) => node.id === selectedId
        ? { ...node, configuration: { ...node.configuration, active } }
        : node),
      isDirty: true
    });
  }

  protected webhookRegistration(nodeId: string): WebhookRegistrationResponse | null {
    return this.webhookRegistrations().find((registration) => registration.nodeId === nodeId) ?? null;
  }

  protected webhookUrl(registration: WebhookRegistrationResponse): string {
    const apiBase = environment.apiBaseUrl.replace(/\/$/, '');
    return `${apiBase}/webhooks/${registration.webhookId}`;
  }

  protected webhookStatus(node: WorkflowNode, registration: WebhookRegistrationResponse): string {
    if (this.state().isDirty) return 'Unsaved changes — save before sending requests.';
    if (!this.workflowActive() || this.readConfig(node, 'active') === 'false') return 'Inactive';
    if (registration.authenticationMode === 'SECRET_TOKEN' && !registration.secretConfigured) {
      return 'Missing required secret — generate one to enable this endpoint.';
    }
    return registration.enabled ? 'Active — waiting for a request.' : 'Inactive';
  }

  protected copyWebhookUrl(registration: WebhookRegistrationResponse): void {
    this.webhookMessage.set(null);
    if (!navigator.clipboard?.writeText) {
      this.webhookMessage.set('Clipboard access is unavailable; copy the URL above.');
      return;
    }
    navigator.clipboard.writeText(this.webhookUrl(registration)).then(
      () => this.webhookMessage.set('Webhook URL copied.'),
      () => this.webhookMessage.set('Unable to access the clipboard; copy the URL above.')
    );
  }

  protected copyWebhookSecret(): void {
    const secret = this.webhookSecretReveal()?.secret;
    if (!secret) return;
    if (!navigator.clipboard?.writeText) {
      this.webhookMessage.set('Clipboard access is unavailable; select and copy the token above.');
      return;
    }
    navigator.clipboard.writeText(secret).then(
      () => this.webhookMessage.set('Secret copied. Store it securely; it will not be shown again.'),
      () => this.webhookMessage.set('Unable to access the clipboard; select and copy the token above.')
    );
  }

  protected generateWebhookSecret(nodeId: string): void {
    const workflowId = this.workflowId();
    if (!workflowId || this.webhookSecretBusy()) return;
    this.webhookSecretBusy.set(true);
    this.webhookMessage.set(null);
    this.webhookSecretReveal.set(null);
    this.workflowApi.regenerateWebhookSecret(workflowId, nodeId).subscribe({
      next: ({ secret }) => {
        this.webhookSecretBusy.set(false);
        this.webhookSecretReveal.set({ nodeId, secret });
        this.loadWebhookRegistrations(workflowId);
      },
      error: () => {
        this.webhookSecretBusy.set(false);
        this.webhookMessage.set('Unable to generate a secret. Save the workflow and try again.');
      }
    });
  }

  protected dismissWebhookSecret(): void {
    this.webhookSecretReveal.set(null);
  }

  private loadWebhookRegistrations(workflowId: string): void {
    this.workflowApi.getWebhooks(workflowId).subscribe({
      next: (registrations) => this.webhookRegistrations.set(registrations),
      error: () => this.webhookMessage.set('Unable to load webhook registration state.')
    });
  }

  protected updateSelectedNodeField(field: 'name' | 'description', event: Event): void {
    const target = event.target as HTMLInputElement | HTMLTextAreaElement | null;
    const value = target?.value ?? '';
    const selectedId = this.selectedNodeId();
    if (!selectedId) {
      return;
    }

    this.pushState({
      ...this.state(),
      nodes: this.state().nodes.map((node) => (node.id === selectedId ? { ...node, [field]: value } : node)),
      isDirty: true
    });
  }

  protected readConfig(node: WorkflowNode, key: string): string {
    const configValue = node.configuration[key];
    return configValue === undefined ? '' : String(configValue);
  }

  protected configEntries(node: WorkflowNode): Array<[string, WorkflowConfigurationValue | undefined]> {
    return Object.entries(node.configuration);
  }

  protected configValueText(value: WorkflowConfigurationValue | undefined): string {
    return value !== null && typeof value === 'object'
      ? JSON.stringify(value)
      : String(value ?? '');
  }

  protected httpEntries(node: WorkflowNode, key: 'queryParameters' | 'headers'): HttpKeyValueEntry[] {
    const raw = node.configuration[key];
    if (Array.isArray(raw)) {
      return raw.map((entry) => {
        const record = this.asRecord(entry);
        return {
          name: typeof record['name'] === 'string' ? record['name'] : '',
          value: typeof record['value'] === 'string' ? record['value'] : '',
          enabled: typeof record['enabled'] === 'boolean' ? record['enabled'] : true
        };
      });
    }
    if (key === 'headers' && typeof raw === 'string') {
      try {
        const legacyHeaders: unknown = JSON.parse(raw);
        if (legacyHeaders && typeof legacyHeaders === 'object' && !Array.isArray(legacyHeaders)) {
          return Object.entries(legacyHeaders).map(([name, value]) => ({
            name,
            value: typeof value === 'string' ? value : String(value),
            enabled: true
          }));
        }
      } catch {
        return [];
      }
    }
    return [];
  }

  protected addHttpEntry(key: 'queryParameters' | 'headers'): void {
    const node = this.selectedNode();
    if (!node) return;
    this.setSelectedConfigValue(key, [...this.httpEntries(node, key), {
      name: '',
      value: '',
      enabled: true
    }]);
  }

  protected removeHttpEntry(key: 'queryParameters' | 'headers', index: number): void {
    const node = this.selectedNode();
    if (!node) return;
    this.setSelectedConfigValue(key, this.httpEntries(node, key).filter((_, entryIndex) => entryIndex !== index));
  }

  protected updateHttpEntry(
    key: 'queryParameters' | 'headers',
    index: number,
    field: 'name' | 'value',
    event: Event
  ): void {
    const value = (event.target as HTMLInputElement | null)?.value ?? '';
    const node = this.selectedNode();
    if (!node) return;
    const entries = this.httpEntries(node, key);
    entries[index] = { ...entries[index], [field]: value };
    this.setSelectedConfigValue(key, entries);
  }

  protected toggleHttpEntry(key: 'queryParameters' | 'headers', index: number, event: Event): void {
    const checked = (event.target as HTMLInputElement | null)?.checked ?? false;
    const node = this.selectedNode();
    if (!node) return;
    const entries = this.httpEntries(node, key);
    entries[index] = { ...entries[index], enabled: checked };
    this.setSelectedConfigValue(key, entries);
  }

  protected updateHttpSetting(key: string, event: Event): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | null;
    if (!target) return;
    let value: WorkflowConfigurationValue = target.value;
    if (key === 'followRedirects' && target instanceof HTMLInputElement) {
      value = target.checked;
    } else if (key === 'timeoutSeconds') {
      const number = Number(target.value);
      value = target.value !== '' && Number.isFinite(number) ? number : target.value;
    }
    this.setSelectedConfigValue(key, value);
  }

  protected updateAiNumber(key: 'temperature' | 'maxOutputTokens', event: Event): void {
    const raw = (event.target as HTMLInputElement | null)?.value ?? '';
    const value = raw.trim() !== '' && Number.isFinite(Number(raw)) ? Number(raw) : raw;
    this.setSelectedConfigValue(key, value);
  }

  protected httpUrlError(node: WorkflowNode): string | null {
    const value = this.readConfig(node, 'url').trim();
    if (!value) return 'Enter an HTTP or HTTPS URL.';
    try {
      const url = new URL(value);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
        return 'Use an HTTP or HTTPS URL without embedded credentials.';
      }
      return null;
    } catch {
      return 'Enter a valid HTTP or HTTPS URL.';
    }
  }

  protected httpTimeoutError(node: WorkflowNode): string | null {
    const timeout = Number(node.configuration['timeoutSeconds'] ?? 30);
    return Number.isInteger(timeout) && timeout >= 1 && timeout <= 3600
      ? null
      : 'Request timeout must be a whole number between 1 and 3600 seconds.';
  }

  protected httpBodyText(node: WorkflowNode): string {
    const body = node.configuration['body'];
    if (typeof body === 'string') return body;
    return JSON.stringify(body ?? {}, null, 2);
  }

  protected updateHttpBody(event: Event): void {
    const text = (event.target as HTMLTextAreaElement | null)?.value ?? '';
    try {
      const body: unknown = JSON.parse(text);
      if (body !== null && typeof body !== 'object') {
        this.httpBodyError.set('JSON body must be an object, array, or null.');
        return;
      }
      this.httpBodyError.set(null);
      this.setSelectedConfigValue('body', body as WorkflowConfigurationValue);
    } catch {
      this.httpBodyError.set('Enter valid JSON for the request body.');
    }
  }

  private asRecord(value: WorkflowConfigurationValue): Record<string, WorkflowConfigurationValue | undefined> {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, WorkflowConfigurationValue | undefined>
      : {};
  }

  protected conditionValueType(node: WorkflowNode): 'string' | 'number' | 'boolean' | 'null' {
    const value = node.configuration['value'];
    if (value === null) return 'null';
    if (typeof value === 'number') return 'number';
    if (typeof value === 'boolean') return 'boolean';
    return 'string';
  }

  protected conditionNeedsValue(node: WorkflowNode): boolean {
    const operator = node.configuration['operator'];
    return operator !== 'is empty' && operator !== 'is not empty';
  }

  protected conditionSummary(node: WorkflowNode): string {
    const field = this.readConfig(node, 'field') || 'Choose a field';
    const operator = this.readConfig(node, 'operator') || 'Choose an operator';
    if (!this.conditionNeedsValue(node)) {
      return `${field} ${operator}`;
    }
    return `${field} ${operator} ${this.readConfig(node, 'value') || '…'}`;
  }

  protected updateConditionValueType(event: Event): void {
    const target = event.target as HTMLSelectElement | null;
    const value = target?.value;
    const typedValue: WorkflowScalarValue = value === 'null'
      ? null
      : value === 'number'
        ? 0
        : value === 'boolean'
          ? false
          : '';
    this.setSelectedConfigValue('value', typedValue);
  }

  protected updateConditionValue(event: Event): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | null;
    if (!target) return;
    const type = this.selectedNode() ? this.conditionValueType(this.selectedNode()!) : 'string';
    const parsedNumber = target.value === '' ? null : Number(target.value);
    const value: WorkflowScalarValue = type === 'number'
      ? (parsedNumber !== null && Number.isFinite(parsedNumber) ? parsedNumber : null)
      : type === 'boolean'
        ? target.value === 'true'
        : target.value;
    this.setSelectedConfigValue('value', value);
  }

  private setSelectedConfigValue(key: string, value: WorkflowConfigurationValue): void {
    const selectedId = this.selectedNodeId();
    if (!selectedId) return;
    this.pushState({
      ...this.state(),
      nodes: this.state().nodes.map((node) =>
        node.id === selectedId
          ? { ...node, configuration: { ...node.configuration, [key]: value } }
          : node
      ),
      isDirty: true
    });
  }

  protected addNode(nodeType: WorkflowNodeType, position: { x: number; y: number }): void {
    const nodeId = `node-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`;
    const newNode = makeNode(nodeId, nodeType, position.x, position.y);
    this.pushState({
      ...this.state(),
      nodes: [...this.state().nodes, newNode],
      selectedNodeId: nodeId,
      selectedConnectionId: null,
      isDirty: true
    });
  }

  protected selectNode(nodeId: string): void {
    this.pushState({
      ...this.state(),
      selectedNodeId: nodeId,
      selectedConnectionId: null
    });
  }

  protected selectConnection(connectionId: string): void {
    this.pushState({
      ...this.state(),
      selectedConnectionId: connectionId,
      selectedNodeId: null
    });
  }

  protected beginConnection(event: PointerEvent, nodeId: string): void {
    event.preventDefault();
    event.stopPropagation();
    const handle = event.currentTarget as HTMLElement;
    const canvas = handle.closest<HTMLElement>('[data-workflow-canvas]');
    const canvasRect = canvas?.getBoundingClientRect();
    const handleRect = handle.getBoundingClientRect();
    const x = canvasRect && canvas
      ? (handleRect.left + handleRect.width / 2 - canvasRect.left + canvas.scrollLeft - this.panX()) / this.zoom()
      : 0;
    const y = canvasRect && canvas
      ? (handleRect.top + handleRect.height / 2 - canvasRect.top + canvas.scrollTop - this.panY()) / this.zoom()
      : 0;
    this.pendingConnection.set({
      sourceNodeId: nodeId,
      sourceHandle: handle.dataset['connectionHandle'] ?? 'output',
      x,
      y
    });
  }

  protected stopHandlePointerDown(event: PointerEvent): void {
    event.preventDefault();
    event.stopPropagation();
  }

  protected handleCanvasPointerMove(event: PointerEvent): void {
    const pending = this.pendingConnection();
    if (pending) {
      const position = this.pointerToCanvas(event);
      this.pendingConnection.set({ ...pending, ...position });
      return;
    }

    const draggingNodeId = this.draggingNodeId();
    if (draggingNodeId) {
      const nextNode = this.state().nodes.find((node) => node.id === draggingNodeId);
      if (!nextNode) {
        return;
      }

      const pointer = this.pointerToCanvas(event);
      const nextX = pointer.x - this.dragOffset().x;
      const nextY = pointer.y - this.dragOffset().y;
      const nextState = {
        ...this.state(),
        nodes: this.state().nodes.map((node) =>
          node.id === draggingNodeId ? { ...node, position: { x: CLAMP(nextX, 0, 1500), y: CLAMP(nextY, 0, 900) } } : node
        ),
        isDirty: true
      };
      this.pushState(nextState);
    }

    if (this.panning()) {
      const previous = this.lastPointer();
      this.panX.update((value) => value + (event.clientX - previous.x));
      this.panY.update((value) => value + (event.clientY - previous.y));
      this.lastPointer.set({ x: event.clientX, y: event.clientY });
    }
  }

  protected handleGlobalPointerMove(event: PointerEvent): void {
    this.handleCanvasPointerMove(event);
  }

  protected handleGlobalPointerUp(event: PointerEvent): void {
    const pending = this.pendingConnection();
    if (pending) {
      const target = document
        .elementFromPoint(event.clientX, event.clientY)
        ?.closest<HTMLElement>('[data-connection-handle="input"][data-connection-node-id]');
      if (target) {
        this.completeConnection(
          pending.sourceNodeId,
          pending.sourceHandle,
          target.dataset['connectionNodeId'] ?? '',
          target.dataset['connectionHandle'] ?? ''
        );
      }
      this.pendingConnection.set(null);
    }

    this.draggingNodeId.set(null);
    this.panning.set(false);
  }

  private completeConnection(sourceNodeId: string, sourceHandle: string, targetNodeId: string, targetHandle: string): void {
    const nodes = this.state().nodes;
    const sourceNode = nodes.find((node) => node.id === sourceNodeId);
    const targetExists = nodes.some((node) => node.id === targetNodeId);
    const validOutput = sourceNode?.type === 'if-condition'
      ? sourceHandle === 'true' || sourceHandle === 'false'
      : sourceHandle === 'output';
    if (!sourceNode || !targetExists || sourceNodeId === targetNodeId || !validOutput || targetHandle !== 'input') {
      return;
    }

    const alreadyConnected = this.state().connections.some(
      (connection) =>
        connection.sourceNodeId === sourceNodeId &&
        connection.sourceHandle === sourceHandle &&
        connection.targetNodeId === targetNodeId &&
        connection.targetHandle === targetHandle
    );
    if (alreadyConnected) {
      return;
    }

    const connection: WorkflowConnection = {
      id: `conn-${Date.now()}-${Math.random().toString(16).slice(2, 6)}`,
      sourceNodeId,
      sourceHandle,
      targetNodeId,
      targetHandle
    };
    this.pushState({
      ...this.state(),
      connections: [...this.state().connections, connection],
      selectedConnectionId: connection.id,
      selectedNodeId: null,
      isDirty: true
    });
  }

  protected startNodeDrag(event: PointerEvent, nodeId: string): void {
    if (this.pendingConnection() || event.button !== 0) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    const node = this.state().nodes.find((item) => item.id === nodeId);
    if (!node) {
      return;
    }

    const pointer = this.pointerToCanvas(event);
    this.draggingNodeId.set(nodeId);
    this.dragOffset.set({
      x: pointer.x - node.position.x,
      y: pointer.y - node.position.y
    });
    this.selectNode(nodeId);
  }

  protected startPanning(event: PointerEvent): void {
    const target = event.target as HTMLElement;
    if (target.closest('button') || target.closest('input') || target.closest('textarea') || target.closest('select') || target.closest('.cursor-grab')) {
      return;
    }
    this.panning.set(true);
    this.lastPointer.set({ x: event.clientX, y: event.clientY });
  }

  protected deleteSelectedNode(): void {
    const selectedId = this.selectedNodeId();
    if (!selectedId) {
      return;
    }

    const hasConnections = this.state().connections.some(
      (connection) => connection.sourceNodeId === selectedId || connection.targetNodeId === selectedId
    );

    if (hasConnections && !window.confirm('This node has connected dependencies. Delete it anyway?')) {
      return;
    }

    this.pushState({
      ...this.state(),
      nodes: this.state().nodes.filter((node) => node.id !== selectedId),
      connections: this.state().connections.filter(
        (connection) => connection.sourceNodeId !== selectedId && connection.targetNodeId !== selectedId
      ),
      selectedNodeId: null,
      selectedConnectionId: null,
      isDirty: true
    });
  }

  protected deleteSelectedConnection(): void {
    const selectedId = this.selectedConnectionId();
    if (!selectedId) {
      return;
    }

    this.pushState({
      ...this.state(),
      connections: this.state().connections.filter((connection) => connection.id !== selectedId),
      selectedConnectionId: null,
      isDirty: true
    });
  }

  protected buildConnectionPath(connection: WorkflowConnection): string {
    const sourceNode = this.state().nodes.find((node) => node.id === connection.sourceNodeId);
    const targetNode = this.state().nodes.find((node) => node.id === connection.targetNodeId);
    if (!sourceNode || !targetNode) {
      return '';
    }

    const { x: startX, y: startY } = this.outputHandlePosition(sourceNode, connection.sourceHandle);
    const endX = targetNode.position.x;
    const endY = targetNode.position.y + 60;
    const midX = (startX + endX) / 2;
    return `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}`;
  }

  protected buildPendingConnectionPath(): string {
    const pending = this.pendingConnection();
    if (!pending) {
      return '';
    }

    const sourceNode = this.state().nodes.find((node) => node.id === pending.sourceNodeId);
    if (!sourceNode) {
      return '';
    }

    const { x: startX, y: startY } = this.outputHandlePosition(sourceNode, pending.sourceHandle);
    const midX = (startX + pending.x) / 2;
    return `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${pending.y}, ${pending.x} ${pending.y}`;
  }

  private outputHandlePosition(node: WorkflowNode, sourceHandle: string): { x: number; y: number } {
    const centerY = sourceHandle === 'true' || sourceHandle === 'output'
      ? 52
      : sourceHandle === 'false' || sourceHandle === 'output-2'
        ? 92
        : 72;
    return { x: node.position.x + 180, y: node.position.y + centerY };
  }

  protected zoomIn(): void {
    this.zoom.set(CLAMP(this.zoom() + 0.1, 0.6, 1.8));
  }

  protected zoomOut(): void {
    this.zoom.set(CLAMP(this.zoom() - 0.1, 0.6, 1.8));
  }

  protected fitCanvas(): void {
    this.zoom.set(1);
    this.panX.set(0);
    this.panY.set(0);
  }

  protected saveState(): void {
    const configurationError = this.workflowConfigurationError();
    if (configurationError) {
      this.saveError.set(configurationError);
      this.saveMessage.set(null);
      return;
    }
    const id = this.workflowId();
    const nextState = { ...this.state(), isDirty: false };
    const payload = {
      name: nextState.workflowName.trim() || 'Untitled Workflow',
      description: nextState.workflowDescription ?? 'No description provided.',
      definition: {
        workflowId: id ?? nextState.workflowId,
        workflowName: nextState.workflowName.trim() || 'Untitled Workflow',
        nodes: nextState.nodes,
        connections: nextState.connections,
        selectedNodeId: nextState.selectedNodeId,
        selectedConnectionId: nextState.selectedConnectionId,
        isDirty: false
      }
    };

    if (!id) {
      this.pushState(nextState);
      this.persistWorkflowState(nextState);
      this.saveMessage.set('Draft saved locally');
      this.saveError.set(null);
      return;
    }

    this.isLoading.set(true);
    this.saveError.set(null);
    this.saveMessage.set(null);

    this.workflowApi.updateWorkflow(id, payload).subscribe({
      next: (updatedWorkflow) => {
        this.isLoading.set(false);
        this.saveMessage.set('Workflow saved');
        this.saveError.set(null);
        this.workflowId.set(updatedWorkflow.id ?? id);
        this.loadWebhookRegistrations(updatedWorkflow.id ?? id);
        const backendState: WorkflowEditorState = {
          workflowId: updatedWorkflow.id ?? id,
          workflowName: updatedWorkflow.name,
          workflowDescription: updatedWorkflow.description ?? nextState.workflowDescription ?? '',
          nodes: nextState.nodes,
          connections: nextState.connections,
          selectedNodeId: nextState.selectedNodeId,
          selectedConnectionId: nextState.selectedConnectionId,
          isDirty: false
        };
        this.pushState(backendState);
        this.persistWorkflowState(backendState);
      },
      error: (error: HttpErrorResponse) => {
        this.isLoading.set(false);
        this.saveError.set(error?.error?.message ?? 'Unable to save workflow');
        this.saveMessage.set(null);
      }
    });
  }

  protected openExecutionDialog(): void {
    this.executionStartError.set(null);
    this.startedExecutionId.set(null);
    this.executionDialogOpen.set(true);
  }

  protected closeExecutionDialog(): void {
    if (!this.isStartingExecution()) {
      this.executionDialogOpen.set(false);
    }
  }

  protected updateExecutionInput(event: Event): void {
    const target = event.target as HTMLTextAreaElement | null;
    this.executionInput.set(target?.value ?? '');
    this.executionStartError.set(null);
  }

  protected updateExecutionTimeout(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    this.executionTimeoutSeconds.set(target?.value ?? '');
  }

  protected onExecutionRetryStarted(executionId: string): void {
    this.startedExecutionId.set(executionId);
  }

  protected startWorkflowExecution(): void {
    if (this.isStartingExecution() || this.executionInputError() || this.executionTimeoutError()) {
      return;
    }
    const configurationError = this.workflowConfigurationError();
    if (configurationError) {
      this.executionStartError.set(configurationError);
      return;
    }
    const workflowId = this.workflowId();
    if (!workflowId || this.state().isDirty || this.isLoading()) {
      this.executionStartError.set(
        !workflowId ? 'Save this workflow before running it.' : 'Save your changes before running the workflow.'
      );
      return;
    }

    let triggerInput: unknown;
    try {
      triggerInput = JSON.parse(this.executionInput());
    } catch {
      this.executionStartError.set('Enter valid JSON before starting the workflow.');
      return;
    }

    this.isStartingExecution.set(true);
    this.executionStartError.set(null);
    this.executionApi.startExecution(workflowId, triggerInput, Number(this.executionTimeoutSeconds())).subscribe({
      next: (execution) => {
        this.isStartingExecution.set(false);
        this.startedExecutionId.set(execution.id);
      },
      error: (error: HttpErrorResponse) => {
        this.isStartingExecution.set(false);
        const message = error.error && typeof error.error === 'object' && 'message' in error.error
          ? String(error.error.message)
          : null;
        this.executionStartError.set(message || 'Unable to start workflow execution. Your JSON input is preserved; please try again.');
      }
    });
  }

  private conditionalConfigurationError(): string | null {
    for (const node of this.state().nodes) {
      if (node.type !== 'if-condition') continue;
      const field = node.configuration['field'];
      const operator = node.configuration['operator'];
      const value = node.configuration['value'];
      if (typeof field !== 'string' || field.trim() === '' || field.split('.').some((part) => part.trim() === '')) {
        return `Condition node "${node.name}" requires a valid field path.`;
      }
      if (typeof operator !== 'string' || !CONDITION_OPERATORS.includes(operator as ConditionalOperator)) {
        return `Condition node "${node.name}" has an unsupported operator.`;
      }
      if (operator !== 'is empty' && operator !== 'is not empty' && value === undefined) {
        return `Condition node "${node.name}" requires a comparison value.`;
      }
      if (
        ['greater than', 'greater than or equal', 'less than', 'less than or equal'].includes(operator)
        && (typeof value === 'boolean' || value === null)
      ) {
        return `Condition node "${node.name}" requires a number or text value for ordering comparisons.`;
      }
    }
    return null;
  }

  private workflowConfigurationError(): string | null {
    const conditionError = this.conditionalConfigurationError();
    if (conditionError) return conditionError;
    const triggers = this.state().nodes.filter((node) =>
      node.type === 'manual-trigger' || node.type === 'webhook-trigger'
    );
    if (triggers.length !== 1) {
      return 'A workflow must contain exactly one Manual Trigger or Webhook Trigger.';
    }
    for (const node of this.state().nodes) {
      if (node.type === 'webhook-trigger') {
        const method = (this.readConfig(node, 'method') || 'POST').toUpperCase();
        if (!['POST', 'GET', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
          return `Webhook Trigger "${node.name}" has an unsupported HTTP method.`;
        }
        if (!['none', 'secret-token'].includes(this.readConfig(node, 'authentication') || 'secret-token')) {
          return `Webhook Trigger "${node.name}" has an unsupported authentication mode.`;
        }
      }
      if (node.type === 'ai-model') {
        if (this.readConfig(node, 'provider') !== 'openai') return `AI Model node "${node.name}" has an unsupported provider.`;
        if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(this.readConfig(node, 'model'))) return `AI Model node "${node.name}" requires a valid model ID.`;
        if (!this.readConfig(node, 'userPrompt').trim()) return `AI Model node "${node.name}" requires a user prompt.`;
        if (this.readConfig(node, 'systemPrompt').length > 16000 || this.readConfig(node, 'userPrompt').length > 32000) return `AI Model node "${node.name}" prompt is too long.`;
        const temperature = node.configuration['temperature'];
        if (typeof temperature !== 'number' || !Number.isFinite(temperature) || temperature < 0 || temperature > 2) return `AI Model node "${node.name}" requires a temperature between 0 and 2.`;
        const tokens = node.configuration['maxOutputTokens'];
        if (typeof tokens !== 'number' || !Number.isInteger(tokens) || tokens < 1 || tokens > 8192) return `AI Model node "${node.name}" requires an output token limit between 1 and 8192.`;
      }
      if (node.type !== 'http-request') continue;
      if (this.httpUrlError(node)) return `HTTP Request node "${node.name}" requires a valid HTTP or HTTPS URL.`;
      if (this.httpTimeoutError(node)) return `HTTP Request node "${node.name}" requires a timeout between 1 and 3600 seconds.`;
      const method = (this.readConfig(node, 'method') || 'GET').toUpperCase();
      if (!['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
        return `HTTP Request node "${node.name}" has an unsupported method.`;
      }
      if (!['auto', 'json', 'text'].includes((this.readConfig(node, 'responseFormat') || 'auto').toLowerCase())) {
        return `HTTP Request node "${node.name}" has an unsupported response format.`;
      }
      if (!['none', 'json'].includes((this.readConfig(node, 'bodyType') || 'none').toLowerCase())) {
        return `HTTP Request node "${node.name}" has an unsupported request body type.`;
      }
      if (this.readConfig(node, 'bodyType') === 'json'
          && !['POST', 'PUT', 'PATCH'].includes(method)) {
        return `HTTP Request node "${node.name}" supports JSON bodies only for POST, PUT, and PATCH.`;
      }
      for (const [label, key] of [
        ['query parameter', 'queryParameters'],
        ['header', 'headers']
      ] as const) {
        const entry = this.httpEntries(node, key).find((item) =>
          !item.name.trim()
          || (key === 'headers' && (
            !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(item.name)
            || ['host', 'content-length', 'transfer-encoding', 'connection'].includes(item.name.toLowerCase())
          ))
        );
        if (entry) return `HTTP Request node "${node.name}" has an invalid ${label} name.`;
      }
    }
    return this.httpBodyError();
  }

  protected undo(): void {
    if (!this.canUndo()) {
      return;
    }
    this.historyIndex.update((index) => index - 1);
  }

  protected redo(): void {
    if (!this.canRedo()) {
      return;
    }
    this.historyIndex.update((index) => index + 1);
  }

  protected pushState(nextState: WorkflowEditorState): void {
    const current = this.state();
    if (JSON.stringify(current) === JSON.stringify(nextState)) {
      return;
    }

    this.history.update((items) => [...items.slice(0, this.historyIndex() + 1), nextState]);
    this.historyIndex.set(this.history().length - 1);
    this.persistWorkflowState(nextState);
  }

  protected loadInitialState(): WorkflowEditorState {
    const workflowId = this.route.snapshot.paramMap.get('id');
    if (workflowId) {
      const saved = this.loadWorkflowState(workflowId);
      if (saved) {
        return saved;
      }
    }

    return createInitialState();
  }

  protected loadWorkflowState(workflowId: string): WorkflowEditorState | null {
    try {
      const raw = localStorage.getItem(this.storageKey);
      if (!raw) {
        return null;
      }

      const entries = JSON.parse(raw) as Record<string, WorkflowEditorState>;
      return entries[workflowId] ?? null;
    } catch {
      return null;
    }
  }

  protected persistWorkflowState(state: WorkflowEditorState): void {
    try {
      const raw = localStorage.getItem(this.storageKey);
      const entries: Record<string, WorkflowEditorState> = raw ? (JSON.parse(raw) as Record<string, WorkflowEditorState>) : {};
      entries[state.workflowId] = state;
      localStorage.setItem(this.storageKey, JSON.stringify(entries));
    } catch {
      // Ignore storage failures in mock-front-end mode.
    }
  }

  protected loadWorkflowFromApi(workflowId: string): void {
    this.isLoading.set(true);
    this.workflowApi.getWorkflow(workflowId).subscribe({
      next: (workflow) => {
        this.isLoading.set(false);
        this.workflowActive.set(workflow.active);
        this.loadWebhookRegistrations(workflowId);
        const existingState = this.loadWorkflowState(workflowId);
        const definition = existingState ?? {
          workflowId,
          workflowName: workflow.name,
          workflowDescription: workflow.description ?? '',
          nodes: [],
          connections: [],
          selectedNodeId: null,
          selectedConnectionId: null,
          isDirty: false
        };

        const parsedDefinition = (workflow as { definition?: Record<string, unknown> }).definition ?? {} as Record<string, unknown>;
        const workflowNameFromDefinition = parsedDefinition['workflowName'];
        const workflowNodes = parsedDefinition['nodes'];
        const workflowConnections = parsedDefinition['connections'];
        const selectedNodeId = parsedDefinition['selectedNodeId'];
        const selectedConnectionId = parsedDefinition['selectedConnectionId'];

        const nextState: WorkflowEditorState = {
          workflowId: workflow.id,
          workflowName: (workflowNameFromDefinition as string | undefined) ?? workflow.name,
          workflowDescription: workflow.description ?? (workflowNameFromDefinition ? '' : definition.workflowDescription ?? ''),
          nodes: Array.isArray(workflowNodes) ? (workflowNodes as WorkflowNode[]) : definition.nodes,
          connections: Array.isArray(workflowConnections) ? (workflowConnections as WorkflowConnection[]) : definition.connections,
          selectedNodeId: (selectedNodeId as string | null | undefined) ?? definition.selectedNodeId,
          selectedConnectionId: (selectedConnectionId as string | null | undefined) ?? definition.selectedConnectionId,
          isDirty: false
        };

        this.history.set([nextState]);
        this.historyIndex.set(0);
        this.persistWorkflowState(nextState);
        this.saveMessage.set('Workflow loaded');
        this.saveError.set(null);
      },
      error: (error: HttpErrorResponse) => {
        this.isLoading.set(false);
        this.saveError.set(error?.error?.message ?? 'Unable to load workflow');
        this.saveMessage.set(null);
      }
    });
  }
}
