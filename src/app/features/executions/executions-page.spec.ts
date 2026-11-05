import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ExecutionApiService } from '../../core/services/execution-api.service';
import { ExecutionsPageComponent } from './executions-page';

describe('ExecutionsPageComponent', () => {
  let fixture: ComponentFixture<ExecutionsPageComponent>;
  let listExecutions: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    const execution = {
      id: 'real-execution-1',
      workflowId: 'workflow-1',
      workflowName: 'Real workflow',
      triggerType: 'MANUAL',
      status: 'SUCCEEDED' as const,
      startedAt: '2026-11-05T10:00:00Z',
      completedAt: '2026-11-05T10:00:02Z',
      durationMs: 2000,
      errorMessage: null,
      errorCode: null,
      timeoutSeconds: 300,
      retryOfExecutionId: null,
      createdAt: '2026-11-05T10:00:00Z'
    };
    listExecutions = vi.fn(() => of({
      content: [execution],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20
    }));
    const api = {
      listExecutions,
      getExecution: vi.fn(() => of(execution)),
      getNodeExecutions: vi.fn(() => of([])),
      getExecutionLogs: vi.fn(() => of([])),
      getExecutionGraph: vi.fn(() => of({ nodes: [], connections: [] })),
      cancelExecution: vi.fn(),
      retryExecution: vi.fn()
    };

    await TestBed.configureTestingModule({
      imports: [ExecutionsPageComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: ExecutionApiService, useValue: api }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(ExecutionsPageComponent);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 10));
    fixture.detectChanges();
  });

  it('loads real paginated execution history instead of local seed records', () => {
    expect(listExecutions).toHaveBeenCalledWith(0, 20, '', null, 'newest');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Real workflow');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Daily Report Generator');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('0m 02s');
  });
});
