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
    let savedDefinition: Record<string, unknown> = {
      nodes: [
        { id: 'node-1', type: 'manual-trigger', name: 'Trigger', description: '', position: { x: 100, y: 100 }, configuration: {} },
        { id: 'node-2', type: 'set-data', name: 'Second', description: '', position: { x: 350, y: 180 }, configuration: {} },
        { id: 'node-3', type: 'set-data', name: 'Third', description: '', position: { x: 600, y: 260 }, configuration: {} },
        { id: 'node-4', type: 'log-message', name: 'Fourth', description: '', position: { x: 850, y: 340 }, configuration: {} },
        { id: 'node-5', type: 'set-data', name: 'Fifth', description: '', position: { x: 1100, y: 420 }, configuration: {} }
      ],
      connections: [
        { id: 'edge-1', sourceNodeId: 'node-1', sourceHandle: 'output', targetNodeId: 'node-2', targetHandle: 'input' }
      ]
    };
    const workflowApi = {
      getWorkflow: vi.fn(() => of({
        id: 'workflow-123',
        name: 'Test workflow',
        description: 'Saved definition',
        active: true,
        createdAt: '2026-11-05T00:00:00Z',
        updatedAt: '2026-11-05T00:00:00Z',
        definition: savedDefinition
      })),
      updateWorkflow: vi.fn((_id: string, payload: { definition?: Record<string, unknown> }) => {
        savedDefinition = payload.definition ?? savedDefinition;
        return of({
          id: 'workflow-123',
          name: 'Test workflow',
          description: 'Saved definition',
          definition: savedDefinition
        });
      })
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

  it('creates an IF node with canonical TRUE/FALSE handles and persists typed conditions', async () => {
    const root = fixture.nativeElement as HTMLElement;
    const addCondition = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('IF / ELSE')) as HTMLButtonElement;
    addCondition.click();
    fixture.detectChanges();

    const conditionNode = root.querySelector('[data-workflow-node-id]:last-of-type') as HTMLElement;
    const handles = Array.from(conditionNode.querySelectorAll('[data-connection-handle]'))
      .map((handle) => handle.getAttribute('data-connection-handle'));
    expect(handles).toContain('input');
    expect(handles).toContain('true');
    expect(handles).toContain('false');
    expect(root.textContent).toContain('status equals active');

    const valueType = root.querySelector('[aria-label="Comparison value type"]') as HTMLSelectElement;
    valueType.value = 'number';
    valueType.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const conditionValue = root.querySelector('[aria-label="Comparison value"]') as HTMLInputElement;
    conditionValue.value = '18';
    conditionValue.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.trim() === 'Save') as HTMLButtonElement;
    saveButton.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const workflowApi = TestBed.inject(WorkflowApiService) as unknown as {
      updateWorkflow: ReturnType<typeof vi.fn>;
    };
    const saved = workflowApi.updateWorkflow.mock.calls.at(-1)?.[1] as {
      definition: { nodes: Array<{ type: string; configuration: Record<string, unknown> }> };
    };
    const condition = saved.definition.nodes.find((node) => node.type === 'if-condition');
    expect(condition?.configuration['value']).toBe(18);
  });

  it('creates and saves a connection from a conditional output using its canonical handle ID', async () => {
    const root = fixture.nativeElement as HTMLElement;
    const addCondition = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('IF / ELSE')) as HTMLButtonElement;
    addCondition.click();
    fixture.detectChanges();

    const conditionNode = Array.from(root.querySelectorAll('[data-workflow-node-id]'))
      .find((node) => node.querySelector('[data-connection-handle="true"]')) as HTMLElement;
    const trueHandle = conditionNode.querySelector('[data-connection-handle="true"]') as HTMLElement;
    const targetHandle = root.querySelector('[data-workflow-node-id="node-1"] [data-connection-handle="input"]') as HTMLElement;
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => targetHandle
    });

    trueHandle.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, button: 0 }));
    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true, clientX: 0, clientY: 0 }));
    Reflect.deleteProperty(document, 'elementFromPoint');
    fixture.detectChanges();

    const saveButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.trim() === 'Save') as HTMLButtonElement;
    saveButton.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const workflowApi = TestBed.inject(WorkflowApiService) as unknown as {
      updateWorkflow: ReturnType<typeof vi.fn>;
    };
    const saved = workflowApi.updateWorkflow.mock.calls.at(-1)?.[1] as {
      definition: { connections: Array<{ sourceNodeId: string; sourceHandle: string }> };
    };
    expect(saved.definition.connections.some((connection) =>
      connection.sourceNodeId === conditionNode.dataset['workflowNodeId'] && connection.sourceHandle === 'true'
    )).toBe(true);
  });

  it('prevents saving an invalid conditional field configuration', () => {
    const root = fixture.nativeElement as HTMLElement;
    const addCondition = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('IF / ELSE')) as HTMLButtonElement;
    addCondition.click();
    fixture.detectChanges();

    const field = Array.from(root.querySelectorAll('label'))
      .find((label) => label.textContent?.trim().startsWith('Field'))
      ?.querySelector('input') as HTMLInputElement;
    field.value = '';
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.trim() === 'Save') as HTMLButtonElement;
    saveButton.click();
    fixture.detectChanges();

    expect(root.textContent).toContain('requires a valid field path');
    const workflowApi = TestBed.inject(WorkflowApiService) as unknown as {
      updateWorkflow: ReturnType<typeof vi.fn>;
    };
    expect(workflowApi.updateWorkflow).not.toHaveBeenCalled();
  });

  it('preserves string comparison values from existing workflows', async () => {
    const root = fixture.nativeElement as HTMLElement;
    const addCondition = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.includes('IF / ELSE')) as HTMLButtonElement;
    addCondition.click();
    fixture.detectChanges();

    const saveButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.trim() === 'Save') as HTMLButtonElement;
    saveButton.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const workflowApi = TestBed.inject(WorkflowApiService) as unknown as {
      updateWorkflow: ReturnType<typeof vi.fn>;
    };
    const saved = workflowApi.updateWorkflow.mock.calls.at(-1)?.[1] as {
      definition: { nodes: Array<{ type: string; configuration: Record<string, unknown> }> };
    };
    const condition = saved.definition.nodes.find((node) => node.type === 'if-condition');
    expect(condition?.configuration['value']).toBe('active');
  });

  it('does not move a node on click or pointer down without movement', () => {
    const root = fixture.nativeElement as HTMLElement;
    const canvas = root.querySelector('[data-workflow-canvas]') as HTMLElement;
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 50, 900, 600));
    const node = root.querySelector('[data-workflow-node-id="node-3"]') as HTMLElement;
    const originalPosition = { left: node.style.left, top: node.style.top };
    const pointerDown = new MouseEvent('pointerdown', {
      bubbles: true,
      button: 0,
      clientX: 710,
      clientY: 330
    });

    node.dispatchEvent(pointerDown);
    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true, clientX: 710, clientY: 330 }));
    node.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(node.style.left).toBe(originalPosition.left);
    expect(node.style.top).toBe(originalPosition.top);
  });

  it('drags first, second, third and fifth nodes smoothly with zoom and canvas scrolling', () => {
    const root = fixture.nativeElement as HTMLElement;
    const canvas = root.querySelector('[data-workflow-canvas]') as HTMLElement;
    const bounds = new DOMRect(100, 50, 900, 600);
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue(bounds);
    canvas.scrollLeft = 35;
    canvas.scrollTop = 20;
    (root.querySelector('[aria-label="Zoom in"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const scale = 1.1;

    const drag = (nodeId: string, start: { x: number; y: number }, target: { x: number; y: number }) => {
      const offset = { x: 24, y: 32 };
      const node = root.querySelector(`[data-workflow-node-id="${nodeId}"]`) as HTMLElement;
      node.dispatchEvent(new MouseEvent('pointerdown', {
        bubbles: true,
        button: 0,
        clientX: bounds.left - canvas.scrollLeft + (start.x + offset.x) * scale,
        clientY: bounds.top - canvas.scrollTop + (start.y + offset.y) * scale
      }));
      fixture.detectChanges();

      window.dispatchEvent(new MouseEvent('pointermove', {
        bubbles: true,
        clientX: bounds.left - canvas.scrollLeft + (target.x + offset.x) * scale,
        clientY: bounds.top - canvas.scrollTop + (target.y + offset.y) * scale
      }));
      fixture.detectChanges();
      window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
      fixture.detectChanges();
      return root.querySelector(`[data-workflow-node-id="${nodeId}"]`) as HTMLElement;
    };

    const movedFirst = drag('node-1', { x: 100, y: 100 }, { x: 60, y: 160 });
    expect(Number.parseFloat(movedFirst.style.left)).toBeCloseTo(60);
    expect(Number.parseFloat(movedFirst.style.top)).toBeCloseTo(160);
    const movedSecond = drag('node-2', { x: 350, y: 180 }, { x: 440, y: 120 });
    expect(Number.parseFloat(movedSecond.style.left)).toBeCloseTo(440);
    expect(Number.parseFloat(movedSecond.style.top)).toBeCloseTo(120);

    const path = root.querySelector('svg path[marker-end]') as SVGPathElement;
    const pathEnd = path.getAttribute('d')?.match(/, ([\d.]+) ([\d.]+)$/);
    expect(pathEnd).not.toBeNull();
    expect(Number(pathEnd?.[1])).toBeCloseTo(440);
    expect(Number(pathEnd?.[2])).toBeCloseTo(180);
    const movedThird = drag('node-3', { x: 600, y: 260 }, { x: 1450, y: 800 });
    expect(Number.parseFloat(movedThird.style.left)).toBeCloseTo(1450);
    expect(Number.parseFloat(movedThird.style.top)).toBeCloseTo(800);
    const movedFifth = drag('node-5', { x: 1100, y: 420 }, { x: 950, y: 500 });
    expect(Number.parseFloat(movedFifth.style.left)).toBeCloseTo(950);
    expect(Number.parseFloat(movedFifth.style.top)).toBeCloseTo(500);
    const boundaryNode = drag('node-5', { x: 950, y: 500 }, { x: 1550, y: 950 });
    expect(Number.parseFloat(boundaryNode.style.left)).toBe(1500);
    expect(Number.parseFloat(boundaryNode.style.top)).toBe(900);
  });

  it('saves dragged positions and reloads them from the saved workflow', async () => {
    const root = fixture.nativeElement as HTMLElement;
    const canvas = root.querySelector('[data-workflow-canvas]') as HTMLElement;
    const bounds = new DOMRect(100, 50, 900, 600);
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue(bounds);
    const node = root.querySelector('[data-workflow-node-id="node-3"]') as HTMLElement;
    node.dispatchEvent(new MouseEvent('pointerdown', {
      bubbles: true,
      button: 0,
      clientX: 720,
      clientY: 330
    }));
    window.dispatchEvent(new MouseEvent('pointermove', {
      bubbles: true,
      clientX: 770,
      clientY: 380
    }));
    window.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
    fixture.detectChanges();

    const saveButton = Array.from(root.querySelectorAll('button'))
      .find((button: HTMLButtonElement) => button.textContent?.trim() === 'Save') as HTMLButtonElement;
    saveButton.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const updateWorkflow = TestBed.inject(WorkflowApiService) as unknown as {
      updateWorkflow: ReturnType<typeof vi.fn>;
    };
    expect(updateWorkflow.updateWorkflow).toHaveBeenCalledOnce();
    const request = updateWorkflow.updateWorkflow.mock.calls[0][1] as {
      definition: { nodes: Array<{ id: string; position: { x: number; y: number } }> };
    };
    expect(request.definition.nodes.find((item) => item.id === 'node-3')?.position).toEqual({ x: 650, y: 310 });

    fixture.destroy();
    fixture = TestBed.createComponent(WorkflowEditorComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const reloadedNode = (fixture.nativeElement as HTMLElement)
      .querySelector('[data-workflow-node-id="node-3"]') as HTMLElement;
    expect(Number.parseFloat(reloadedNode.style.left)).toBe(650);
    expect(Number.parseFloat(reloadedNode.style.top)).toBe(310);
  });
});
