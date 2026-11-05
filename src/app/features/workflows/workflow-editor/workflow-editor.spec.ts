import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { WorkflowApiService } from '../../../core/services/workflow-api.service';
import { ExecutionApiService } from '../../../core/services/execution-api.service';
import { WorkflowEditorComponent } from './workflow-editor';

describe('WorkflowEditor execution flow', () => {
  let fixture: ComponentFixture<WorkflowEditorComponent>;
  let executionApi: {
    startExecution: ReturnType<typeof vi.fn>;
    getExecution: ReturnType<typeof vi.fn>;
    getNodeExecutions: ReturnType<typeof vi.fn>;
    getExecutionLogs: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    const workflowApi = {
      getWorkflow: vi.fn(() => of({
        id: 'workflow-123',
        name: 'Test workflow',
        description: 'Saved definition',
        active: true,
        createdAt: '2026-11-05T00:00:00Z',
        updatedAt: '2026-11-05T00:00:00Z',
        definition: {
          nodes: [{ id: 'trigger-1', type: 'manual-trigger', name: 'Manual Trigger', description: '', position: { x: 0, y: 0 }, configuration: {} }],
          connections: []
        }
      }))
    };
    executionApi = {
      startExecution: vi.fn(() => of({ id: 'execution-456', status: 'QUEUED', createdAt: '2026-11-05T00:00:00Z' })),
      getExecution: vi.fn(() => of({
        id: 'execution-456',
        workflowId: 'workflow-123',
        workflowName: 'Test workflow',
        triggerType: 'MANUAL',
        status: 'SUCCEEDED',
        startedAt: null,
        completedAt: null,
        durationMs: 1,
        errorMessage: null,
        createdAt: '2026-11-05T00:00:00Z'
      })),
      getNodeExecutions: vi.fn(() => of([])),
      getExecutionLogs: vi.fn(() => of([]))
    };

    await TestBed.configureTestingModule({
      imports: [WorkflowEditorComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ id: 'workflow-123' }) }
          }
        },
        { provide: WorkflowApiService, useValue: workflowApi },
        { provide: ExecutionApiService, useValue: executionApi }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(WorkflowEditorComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('opens the existing Run / Test button dialog with the example JSON', () => {
    const root = fixture.nativeElement as HTMLElement;
    const runButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('Run / Test')) as HTMLButtonElement;
    runButton.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="dialog"]')).toBeTruthy();
    expect((fixture.nativeElement.querySelector('#execution-trigger-input') as HTMLTextAreaElement).value)
      .toContain('"user": "Alex"');
    expect((fixture.nativeElement.querySelector('#execution-trigger-input') as HTMLTextAreaElement).value)
      .toContain('"message": "Hello FlowForge"');
  });

  it('validates edited JSON and starts execution with the saved workflow ID and parsed input', async () => {
    const root = fixture.nativeElement as HTMLElement;
    const runButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('Run / Test')) as HTMLButtonElement;
    runButton.click();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#execution-trigger-input') as HTMLTextAreaElement;
    input.value = '{"user":';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent)
      .toContain('Enter valid JSON');

    input.value = '{"user":"Mina","count":2}';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const executeButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('Execute workflow')) as HTMLButtonElement;
    expect(executeButton.disabled).toBe(false);
    executeButton.click();
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 10));
    fixture.detectChanges();

    expect(executionApi.startExecution).toHaveBeenCalledOnce();
    expect(executionApi.startExecution).toHaveBeenCalledWith('workflow-123', { user: 'Mina', count: 2 });
    expect(fixture.nativeElement.textContent).toContain('Open execution details');
    expect(fixture.nativeElement.textContent).toContain('execution-456');
  });

  it('keeps the entered JSON available when the backend rejects the execution request', () => {
    executionApi.startExecution.mockReturnValue(throwError(() => ({
      error: { message: 'Workflow contains an unsupported node type.' }
    })));
    const root = fixture.nativeElement as HTMLElement;
    const runButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('Run / Test')) as HTMLButtonElement;
    runButton.click();
    fixture.detectChanges();

    const input = root.querySelector('#execution-trigger-input') as HTMLTextAreaElement;
    input.value = '{"user":"Alex","message":"keep me"}';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('Execute workflow'))
      ?.click();
    fixture.detectChanges();

    expect(input.value).toBe('{"user":"Alex","message":"keep me"}');
    expect(root.querySelector('[role="alert"]')?.textContent)
      .toContain('Workflow contains an unsupported node type.');
  });
});
