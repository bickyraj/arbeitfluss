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
      config: {
        provider: 'openai',
        model: 'gpt-4.1-mini',
        systemPrompt: '',
        userPrompt: 'Summarize: {{input}}',
        temperature: 0.7,
        maxOutputTokens: 512
      }
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
  standalone: true,
  templateUrl: 'workflow-editor.html'
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
