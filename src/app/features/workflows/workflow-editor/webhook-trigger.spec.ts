import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ExecutionApiService } from '../../../core/services/execution-api.service';
import { WorkflowApiService } from '../../../core/services/workflow-api.service';
import { WorkflowEditorComponent } from './workflow-editor';

describe('WorkflowEditor Webhook Trigger configuration', () => {
  let fixture: ComponentFixture<WorkflowEditorComponent>;
  let getWebhooks: ReturnType<typeof vi.fn>;
  let regenerateWebhookSecret: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    getWebhooks = vi.fn(() => of([{
      nodeId: 'webhook-node',
      webhookId: 'opaque-webhook-id',
      httpMethod: 'POST' as const,
      authenticationMode: 'SECRET_TOKEN' as const,
      enabled: true,
      secretConfigured: true
    }]));
    regenerateWebhookSecret = vi.fn(() => of({ secret: 'one-time-secret-value' }));

    await TestBed.configureTestingModule({
      imports: [WorkflowEditorComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'workflow-123' }) } }
        },
        {
          provide: WorkflowApiService,
          useValue: {
            getWorkflow: vi.fn(() => of({
              id: 'workflow-123',
              name: 'Webhook workflow',
              description: 'External event flow',
              active: true,
              createdAt: '2026-11-05T00:00:00Z',
              updatedAt: '2026-11-05T00:00:00Z',
              definition: {
                workflowId: 'workflow-123',
                workflowName: 'Webhook workflow',
                nodes: [{
                  id: 'webhook-node',
                  type: 'webhook-trigger',
                  name: 'Webhook Trigger',
                  description: '',
                  position: { x: 100, y: 120 },
                  configuration: {
                    method: 'POST',
                    authentication: 'secret-token',
                    active: true,
                    responseMode: 'immediate'
                  }
                }],
                connections: [],
                selectedNodeId: 'webhook-node',
                selectedConnectionId: null,
                isDirty: false
              }
            })),
            getWebhooks,
            regenerateWebhookSecret
          }
        },
        {
          provide: ExecutionApiService,
          useValue: {
            startExecution: vi.fn(() => of({ id: 'execution-id', status: 'QUEUED', createdAt: '' })),
            getExecution: vi.fn(() => of(null)),
            getExecutionGraph: vi.fn(() => of({ nodes: [], connections: [] })),
            getNodeExecutions: vi.fn(() => of([])),
            getExecutionLogs: vi.fn(() => of([]))
          }
        }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(WorkflowEditorComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('renders the backend URL and exposes a one-time generated secret that can be hidden', () => {
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('/api/webhooks/opaque-webhook-id');
    expect(root.textContent).toContain('Active — waiting for a request.');
    expect(root.textContent).not.toContain('one-time-secret-value');

    const generate = Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Regenerate Secret')) as HTMLButtonElement;
    generate.click();
    fixture.detectChanges();

    expect(regenerateWebhookSecret).toHaveBeenCalledWith('workflow-123', 'webhook-node');
    expect(root.textContent).toContain('one-time-secret-value');
    const hide = Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Hide secret')) as HTMLButtonElement;
    hide.click();
    fixture.detectChanges();
    expect(root.textContent).not.toContain('one-time-secret-value');
  });
});
