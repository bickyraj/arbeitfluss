import { ComponentFixture, TestBed } from '@angular/core/testing';
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
      ]))
    };

    await TestBed.configureTestingModule({
      imports: [ExecutionDetailsComponent],
      providers: [{ provide: ExecutionApiService, useValue: api }]
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
    expect(root.textContent).toContain('SKIPPED · branch not selected');
    expect(root.textContent).toContain('selectedBranch');
    expect(root.textContent).toContain('TRUE');
    const skippedBadge = Array.from(root.querySelectorAll('span'))
      .find((span) => span.textContent?.includes('SKIPPED')) as HTMLElement;
    expect(skippedBadge.classList.contains('bg-slate-100')).toBe(true);
    expect(skippedBadge.classList.contains('text-rose-700')).toBe(false);
  });
});
