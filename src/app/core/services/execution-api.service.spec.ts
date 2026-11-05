import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ExecutionApiService } from './execution-api.service';

describe('ExecutionApiService', () => {
  let api: ExecutionApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    api = TestBed.inject(ExecutionApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the workflow ID and triggerInput in the backend request shape', () => {
    api.startExecution('workflow-123', { user: 'Alex', message: 'Hello FlowForge' }).subscribe();

    const request = http.expectOne('/api/workflows/workflow-123/executions');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      triggerInput: { user: 'Alex', message: 'Hello FlowForge' }
    });
    request.flush({ id: 'execution-456', status: 'QUEUED', createdAt: '2026-11-05T00:00:00Z' });
  });

  it('includes the selected execution timeout when provided', () => {
    api.startExecution('workflow-123', {}, 45).subscribe();
    const request = http.expectOne('/api/workflows/workflow-123/executions');
    expect(request.request.body).toEqual({ triggerInput: {}, timeoutSeconds: 45 });
    request.flush({ id: 'execution-789', status: 'QUEUED', createdAt: '2026-11-05T00:00:00Z' });
  });

  it('uses the global paginated history contract with filters and sorting', () => {
    api.listExecutions(1, 20, 'sales', 'FAILED', 'duration-desc').subscribe();

    const request = http.expectOne((req) => req.url === '/api/executions' && req.params.get('page') === '1');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('search')).toBe('sales');
    expect(request.request.params.get('status')).toBe('FAILED');
    expect(request.request.params.get('sort')).toBe('duration-desc');
    request.flush({ content: [], totalElements: 0, totalPages: 1, number: 1, size: 20 });
  });

  it('posts cancel and retry actions against the selected execution', () => {
    api.cancelExecution('execution-1').subscribe();
    const cancelRequest = http.expectOne('/api/executions/execution-1/cancel');
    expect(cancelRequest.request.method).toBe('POST');
    cancelRequest.flush({ id: 'execution-1', status: 'CANCELLING' });

    api.retryExecution('execution-1').subscribe();
    const retryRequest = http.expectOne('/api/executions/execution-1/retry');
    expect(retryRequest.request.method).toBe('POST');
    retryRequest.flush({ id: 'execution-2', status: 'QUEUED', createdAt: '2026-11-05T00:00:00Z' });
  });
});
