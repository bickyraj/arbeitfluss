import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { ExecutionApiService } from '../../../core/services/execution-api.service';
import { ExecutionDetailsComponent } from '../../executions/execution-details';
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
import { WorkflowApiService } from '../../../core/services/workflow-api.service';
import type {
  NodeLibraryItem,
  WorkflowConnection,
  WorkflowEditorState,
  WorkflowNode,
  WorkflowNodeConfiguration,
  WorkflowNodeType
} from '../../../core/models/workflow-editor.model';

const NODE_LIBRARY: NodeLibraryItem[] = [
  { id: 'manual-trigger', name: 'Manual Trigger', description: 'Starts a workflow on demand.', category: 'Triggers' },
  { id: 'schedule-trigger', name: 'Schedule Trigger', description: 'Runs on a recurring schedule.', category: 'Triggers' },
  { id: 'webhook-trigger', name: 'Webhook Trigger', description: 'Receives payloads from external apps.', category: 'Triggers' },
  { id: 'http-request', name: 'HTTP Request', description: 'Calls an external API endpoint.', category: 'Actions' },
  { id: 'send-email', name: 'Send Email', description: 'Dispatches an email to a recipient.', category: 'Actions' },
  { id: 'database-query', name: 'Database Query', description: 'Fetches or updates database records.', category: 'Actions' },
  { id: 'log-message', name: 'Log Message', description: 'Writes an execution log entry.', category: 'Actions' },
  { id: 'if-condition', name: 'IF Condition', description: 'Branches logic based on a condition.', category: 'Logic' },
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
      description: 'Accepts events from an external webhook.',
      config: { url: 'https://example.com/webhook', method: 'POST' }
    },
    'http-request': {
      name: 'HTTP Request',
      description: 'Calls an external API endpoint.',
      config: { method: 'POST', url: 'https://api.example.com/v1/endpoint', headers: '{}', query: '', body: '{}' }
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
      name: 'IF Condition',
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
    { id: 'conn-2', sourceNodeId: 'node-2', sourceHandle: 'output', targetNodeId: 'node-3', targetHandle: 'input' },
    { id: 'conn-3', sourceNodeId: 'node-2', sourceHandle: 'output-2', targetNodeId: 'node-4', targetHandle: 'input' }
  ],
  selectedNodeId: 'node-1',
  selectedConnectionId: null,
  isDirty: false
});

const CLAMP = (value: number, min: number, max: number): number => Math.min(Math.max(value, min), max);

@Component({
  selector: 'app-workflow-editor',
  standalone: true,
  imports: [LucideAngularModule, RouterLink, ExecutionDetailsComponent],
  template: `
    <div class="flex min-h-screen flex-col bg-[#F8FAFC] text-slate-900" (window:pointerup)="handleGlobalPointerUp($event)">
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
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50" (click)="zoomOut()">
              <lucide-icon [img]="zoomOutIcon" class="h-4 w-4" />
            </button>
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50" (click)="zoomIn()">
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
            class="relative min-h-[640px] flex-1 overflow-hidden"
            data-workflow-canvas
            [style.background-size]="'18px 18px'"
            [style.background-image]="'radial-gradient(circle, rgba(148, 163, 184, 0.28) 1.2px, transparent 0)'"
            (dragover)="allowCanvasDrop($event)"
            (drop)="onCanvasDrop($event)"
            (pointermove)="handleCanvasPointerMove($event)"
            (mousedown)="startPanning($event)"
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
                @case ('http-request') {
                  <div class="space-y-3">
                    <label class="block text-sm font-medium text-slate-700">Method<select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'method')" (change)="updateSelectedNodeConfig('method', $event)"><option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option></select></label>
                    <label class="block text-sm font-medium text-slate-700">URL<input type="text" [value]="readConfig(node, 'url')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('url', $event)" /></label>
                    <label class="block text-sm font-medium text-slate-700">Headers<textarea rows="3" [value]="readConfig(node, 'headers')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('headers', $event)"></textarea></label>
                    <label class="block text-sm font-medium text-slate-700">Body<textarea rows="3" [value]="readConfig(node, 'body')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('body', $event)"></textarea></label>
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
                    <label class="block text-sm font-medium text-slate-700">Field<input type="text" [value]="readConfig(node, 'field')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('field', $event)" /></label>
                    <label class="block text-sm font-medium text-slate-700">Operator<select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="readConfig(node, 'operator')" (change)="updateSelectedNodeConfig('operator', $event)"><option>equals</option><option>not equals</option><option>greater than</option><option>less than</option></select></label>
                    <label class="block text-sm font-medium text-slate-700">Comparison Value<input type="text" [value]="readConfig(node, 'value')" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateSelectedNodeConfig('value', $event)" /></label>
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

            @if (!workflowId() || state().isDirty) {
              <p role="status" class="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
                {{ !workflowId() ? 'Save this workflow before running it.' : 'Save your changes before running. Execution uses the saved workflow graph.' }}
              </p>
            }
            <p class="mt-4 rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-600">
              Currently executable node types are Manual Trigger, Set Data, and Log Message. Other node types, including HTTP Request and IF Condition, are not executable yet.
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
                <app-execution-details [executionId]="executionId" />
              </div>
            } @else {
              <div class="mt-5 flex flex-wrap justify-end gap-3">
                <button type="button" class="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50" [disabled]="isStartingExecution()" (click)="closeExecutionDialog()">Cancel</button>
                <button
                  type="button"
                  class="rounded-xl bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:cursor-not-allowed disabled:opacity-50"
                  [disabled]="isStartingExecution() || !!executionInputError() || !workflowId() || state().isDirty || isLoading()"
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
  protected readonly storageKey = 'flowforge-workflow-states';
  protected readonly history = signal<WorkflowEditorState[]>([]);
  protected readonly historyIndex = signal(0);
  protected readonly isLoading = signal(false);
  protected readonly saveMessage = signal<string | null>(null);
  protected readonly saveError = signal<string | null>(null);
  protected readonly workflowId = signal<string | null>(null);
  protected readonly executionDialogOpen = signal(false);
  protected readonly executionInput = signal('{\n  "user": "Alex",\n  "message": "Hello FlowForge"\n}');
  protected readonly isStartingExecution = signal(false);
  protected readonly executionStartError = signal<string | null>(null);
  protected readonly startedExecutionId = signal<string | null>(null);
  protected readonly executionInputError = computed(() => {
    try {
      JSON.parse(this.executionInput());
      return null;
    } catch {
      return 'Enter valid JSON before starting the workflow.';
    }
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

  protected nodeLibraryByCategory(category: string): NodeLibraryItem[] {
    return this.filteredLibrary().filter((item) => item.category === category);
  }

  protected getNodeIcon(nodeType: WorkflowNodeType): LucideIconData {
    const mapping: Record<WorkflowNodeType, LucideIconData> = {
      'manual-trigger': Zap,
      'schedule-trigger': RotateCcw,
      'webhook-trigger': Send,
      'http-request': Download,
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
    const x = (event.clientX - bounds.left - this.panX()) / this.zoom();
    const y = (event.clientY - bounds.top - this.panY()) / this.zoom();
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

  protected configEntries(node: WorkflowNode): Array<[string, string | number | boolean | undefined]> {
    return Object.entries(node.configuration) as Array<[string, string | number | boolean | undefined]>;
  }

  protected configValueText(value: string | number | boolean | undefined): string {
    return String(value ?? '');
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
    const x = canvasRect ? (handleRect.left + handleRect.width / 2 - canvasRect.left - this.panX()) / this.zoom() : 0;
    const y = canvasRect ? (handleRect.top + handleRect.height / 2 - canvasRect.top - this.panY()) / this.zoom() : 0;
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

  protected handleCanvasPointerMove(event: MouseEvent): void {
    const pending = this.pendingConnection();
    if (pending) {
      const target = event.currentTarget as HTMLElement;
      const rect = target.getBoundingClientRect();
      const x = (event.clientX - rect.left - this.panX()) / this.zoom();
      const y = (event.clientY - rect.top - this.panY()) / this.zoom();
      this.pendingConnection.set({ ...pending, x, y });
      return;
    }

    const draggingNodeId = this.draggingNodeId();
    if (draggingNodeId) {
      const target = event.currentTarget as HTMLElement;
      const rect = target.getBoundingClientRect();
      const nextNode = this.state().nodes.find((node) => node.id === draggingNodeId);
      if (!nextNode) {
        return;
      }

      const nextX = (event.clientX - rect.left - this.dragOffset().x) / this.zoom();
      const nextY = (event.clientY - rect.top - this.dragOffset().y) / this.zoom();
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
    const sourceExists = nodes.some((node) => node.id === sourceNodeId);
    const targetExists = nodes.some((node) => node.id === targetNodeId);
    if (!sourceExists || !targetExists || sourceNodeId === targetNodeId || sourceHandle !== 'output' || targetHandle !== 'input') {
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

  protected startNodeDrag(event: MouseEvent, nodeId: string): void {
    if (this.pendingConnection()) {
      return;
    }

    const target = event.currentTarget as HTMLElement;
    const rect = target.getBoundingClientRect();
    this.draggingNodeId.set(nodeId);
    this.dragOffset.set({
      x: (event.clientX - rect.left) / this.zoom(),
      y: (event.clientY - rect.top) / this.zoom()
    });
    this.selectNode(nodeId);
  }

  protected startPanning(event: MouseEvent): void {
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

    const startX = sourceNode.position.x + 180;
    const startY = sourceNode.position.y + 60;
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

    const startX = sourceNode.position.x + 180;
    const startY = sourceNode.position.y + 60;
    const midX = (startX + pending.x) / 2;
    return `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${pending.y}, ${pending.x} ${pending.y}`;
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
    const id = this.workflowId();
    const nextState = { ...this.state(), isDirty: false };
    const payload = {
      name: nextState.workflowName.trim() || 'Untitled Workflow',
      description: nextState.workflowDescription ?? 'No description provided.',
      active: true,
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

  protected startWorkflowExecution(): void {
    if (this.isStartingExecution() || this.executionInputError()) {
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
    this.executionApi.startExecution(workflowId, triggerInput).subscribe({
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
