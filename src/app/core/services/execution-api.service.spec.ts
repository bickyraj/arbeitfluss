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
});
