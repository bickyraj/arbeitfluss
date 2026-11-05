import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ExecutionApiService } from '../../core/services/execution-api.service';
import { ExecutionDetailsComponent } from './execution-details';

describe('ExecutionDetailsComponent branch monitoring', () => {
  let fixture: ComponentFixture<ExecutionDetailsComponent>;

  beforeEach(async () => {
    const api = {
      getExecution: vi.fn(() => of({
        id: 'execution-1',
        workflowId: 'workflow-1',
        workflowName: 'Branch workflow',
        triggerType: 'MANUAL',
        status: 'SUCCEEDED',
        startedAt: null,
        completedAt: null,
        durationMs: 1,
        errorMessage: null,
        errorCode: null,
        timeoutSeconds: 300,
        retryOfExecutionId: null,
        createdAt: '2026-11-05T00:00:00Z'
      })),
      getNodeExecutions: vi.fn(() => of([
        {
          id: 'node-1',
          executionId: 'execution-1',
          workflowNodeId: 'unselected-path',
          nodeType: 'log-message',
          status: 'SKIPPED',
          inputData: null,
          outputData: null,
          startedAt: null,
          completedAt: null,
          durationMs: 0,
          errorMessage: null,
          createdAt: '2026-11-05T00:00:00Z'
        },
        {
          id: 'node-http',
          executionId: 'execution-1',
          workflowNodeId: 'request',
          nodeType: 'http-request',
          status: 'SUCCEEDED',
          inputData: null,
          outputData: { body: 'x'.repeat(31_000) },
          startedAt: null,
          completedAt: null,
          durationMs: 1,
          errorMessage: null,
          createdAt: '2026-11-05T00:00:00Z'
        },
        {
          id: 'node-http-error',
          executionId: 'execution-1',
          workflowNodeId: 'failed-request',
          nodeType: 'http-request',
          status: 'FAILED',
          inputData: null,
          outputData: {
            statusCode: 503,
            responseFormat: 'Text',
            headers: { 'content-type': 'text/plain' },
            body: 'temporarily unavailable',
            success: false
          },
          startedAt: null,
          completedAt: null,
          durationMs: 125,
          errorMessage: 'HTTP request returned status 503.',
          createdAt: '2026-11-05T00:00:00Z'
        }
      ])),
      getExecutionLogs: vi.fn(() => of([
        {
          id: 'log-1',
          executionId: 'execution-1',
          nodeExecutionId: 'node-2',
          level: 'INFO',
          message: 'Condition selected the TRUE branch.',
          details: {
            nodeType: 'if-condition',
            field: 'age',
            operator: 'greater than or equal',
            result: true,
            selectedBranch: 'TRUE'
          },
          createdAt: '2026-11-05T00:00:00Z'
        }
      ])),
      getExecutionGraph: vi.fn(() => of({
        nodes: [
          { id: 'unselected-path', type: 'log-message', name: 'Skipped branch', position: { x: 100, y: 100 } },
          { id: 'request', type: 'http-request', name: 'Large request output', position: { x: 360, y: 100 } },
          { id: 'failed-request', type: 'http-request', name: 'Failed request', position: { x: 620, y: 100 } }
        ],
        connections: []
      }))
    };

    await TestBed.configureTestingModule({
      imports: [ExecutionDetailsComponent],
      providers: [provideRouter([]), { provide: ExecutionApiService, useValue: api }]
    }).compileComponents();
    fixture = TestBed.createComponent(ExecutionDetailsComponent);
    fixture.componentRef.setInput('executionId', 'execution-1');
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 10));
    fixture.detectChanges();
  });

  it('distinguishes skipped nodes and displays the selected branch log', () => {
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('SKIPPED');
    expect(root.textContent).toContain('TRUE');
    expect(root.textContent).toContain('HTTP status: 503');
    expect(root.textContent).toContain('Response format: Text');
    expect(root.textContent).toContain('125 ms');
    expect(root.textContent).toContain('HTTP request returned status 503.');
    expect(root.querySelector('[data-node-id="unselected-path"]')?.getAttribute('data-status')).toBe('SKIPPED');
    const detailsButton = Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Show structured details')) as HTMLButtonElement;
    detailsButton.click();
    fixture.detectChanges();
    expect(root.textContent).toContain('selectedBranch');
  });
});

describe('ExecutionDetailsComponent reliability actions', () => {
  it('requests cooperative cancellation for an active run', async () => {
    const active = {
      id: 'active-1',
      workflowId: 'workflow-1',
      workflowName: 'Active workflow',
      triggerType: 'MANUAL',
      status: 'RUNNING' as const,
      startedAt: '2026-11-05T00:00:00Z',
      completedAt: null,
      durationMs: null,
      errorMessage: null,
      errorCode: null,
      timeoutSeconds: 300,
      retryOfExecutionId: null,
      createdAt: '2026-11-05T00:00:00Z'
    };
    const api = {
      getExecution: vi.fn(() => of(active)),
      getNodeExecutions: vi.fn(() => of([])),
      getExecutionLogs: vi.fn(() => of([])),
      getExecutionGraph: vi.fn(() => of({ nodes: [], connections: [] })),
      cancelExecution: vi.fn(() => of({ ...active, status: 'CANCELLING' as const })),
      retryExecution: vi.fn()
    };
    await TestBed.configureTestingModule({
      imports: [ExecutionDetailsComponent],
      providers: [provideRouter([]), { provide: ExecutionApiService, useValue: api }]
    }).compileComponents();
    const fixture = TestBed.createComponent(ExecutionDetailsComponent);
    fixture.componentRef.setInput('executionId', 'active-1');
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 10));
    fixture.detectChanges();

    const cancelButton = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find((button) => button.textContent?.includes('Cancel'));
    expect(cancelButton).toBeTruthy();
    cancelButton?.click();
    expect(api.cancelExecution).toHaveBeenCalledWith('active-1');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('CANCELLING');
    fixture.destroy();
  });

  it('creates a new retry execution only for a failed run and emits its ID', async () => {
    const api = {
      getExecution: vi.fn(() => of({
        id: 'failed-1',
        workflowId: 'workflow-1',
        workflowName: 'Failed workflow',
        triggerType: 'MANUAL',
        status: 'FAILED' as const,
        startedAt: '2026-11-05T00:00:00Z',
        completedAt: '2026-11-05T00:00:01Z',
        durationMs: 1000,
        errorMessage: 'Node failed',
        errorCode: 'EXECUTION_FAILED',
        timeoutSeconds: 300,
        retryOfExecutionId: null,
        createdAt: '2026-11-05T00:00:00Z'
      })),
      getNodeExecutions: vi.fn(() => of([])),
      getExecutionLogs: vi.fn(() => of([])),
      getExecutionGraph: vi.fn(() => of({ nodes: [], connections: [] })),
      cancelExecution: vi.fn(),
      retryExecution: vi.fn(() => of({
        id: 'retry-1',
        status: 'QUEUED' as const,
        createdAt: '2026-11-05T00:00:02Z'
      }))
    };

    await TestBed.configureTestingModule({
      imports: [ExecutionDetailsComponent],
      providers: [provideRouter([]), { provide: ExecutionApiService, useValue: api }]
    }).compileComponents();
    const fixture = TestBed.createComponent(ExecutionDetailsComponent);
    fixture.componentRef.setInput('executionId', 'failed-1');
    const retriedIds: string[] = [];
    fixture.componentInstance.retryStarted.subscribe((id) => retriedIds.push(id));
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 10));
    fixture.detectChanges();

    const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    const retryButton = Array.from(buttons)
      .find((button) => button.textContent?.includes('Retry')) as HTMLButtonElement;
    expect(retryButton).toBeTruthy();
    retryButton.click();
    expect(api.retryExecution).toHaveBeenCalledWith('failed-1');
    expect(retriedIds).toEqual(['retry-1']);
    fixture.destroy();
  });
});
