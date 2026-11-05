import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { WorkflowApiService } from './workflow-api.service';

describe('WorkflowApiService webhook APIs', () => {
  let service: WorkflowApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(WorkflowApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads public registration metadata without a secret and explicitly regenerates a secret', () => {
    service.getWebhooks('workflow-1').subscribe((registrations) => {
      expect(registrations[0].webhookId).toBe('opaque-webhook-id');
      expect(registrations[0].secretConfigured).toBe(true);
    });
    const metadata = http.expectOne('/api/workflows/workflow-1/webhooks');
    expect(metadata.request.method).toBe('GET');
    metadata.flush([{
      nodeId: 'webhook-node',
      webhookId: 'opaque-webhook-id',
      httpMethod: 'POST',
      authenticationMode: 'SECRET_TOKEN',
      enabled: true,
      secretConfigured: true
    }]);

    service.regenerateWebhookSecret('workflow-1', 'webhook-node').subscribe(({ secret }) => {
      expect(secret).toBe('one-time-token');
    });
    const rotation = http.expectOne('/api/workflows/workflow-1/webhooks/webhook-node/secret');
    expect(rotation.request.method).toBe('POST');
    rotation.flush({ secret: 'one-time-token' });
  });
});
