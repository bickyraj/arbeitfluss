import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { adminAuthInterceptor } from './admin-auth.interceptor';
import { AdminAuthService } from './admin-auth.service';

describe('AdminAuthService', () => {
  let auth: AdminAuthService;
  let client: HttpClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([adminAuthInterceptor])),
        provideHttpClientTesting()
      ]
    });
    auth = TestBed.inject(AdminAuthService);
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('keeps the admin token in memory and attaches it only to management API requests', () => {
    auth.signIn('deployment-admin-token').subscribe();
    const verification = http.expectOne('/api/auth/verify');
    expect(verification.request.method).toBe('POST');
    expect(verification.request.headers.getAll('Authorization')).toEqual(['Bearer deployment-admin-token']);
    expect(verification.request.body).toBeNull();
    expect(auth.authenticated()).toBeNull();
    verification.flush(null);
    expect(auth.authenticated()).toBe('deployment-admin-token');

    client.get('/api/workflows').subscribe();
    const workflows = http.expectOne('/api/workflows');
    expect(workflows.request.headers.get('Authorization')).toBe('Bearer deployment-admin-token');
    workflows.flush([]);

    client.post('/api/webhooks/random-id', {}).subscribe();
    const webhook = http.expectOne('/api/webhooks/random-id');
    expect(webhook.request.headers.has('Authorization')).toBe(false);
    webhook.flush({ accepted: true });

    auth.signOut();
    expect(auth.authenticated()).toBeNull();
    expect(auth.token).toBeNull();

    client.get('/api/workflows').subscribe();
    const signedOutRequest = http.expectOne('/api/workflows');
    expect(signedOutRequest.request.headers.has('Authorization')).toBe(false);
    signedOutRequest.flush([]);
  });

  it('does not authenticate when verification fails', () => {
    const onError = vi.fn();
    auth.signIn('invalid-test-token').subscribe({ error: onError });
    http.expectOne('/api/auth/verify').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(onError).toHaveBeenCalledOnce();
    expect(auth.authenticated()).toBeNull();
    expect(auth.token).toBeNull();
    client.get('/api/workflows').subscribe();
    const request = http.expectOne('/api/workflows');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush([]);
  });

  it('verifies a new token explicitly without the interceptor overwriting it with the current token', () => {
    auth.signIn('first-test-token').subscribe();
    http.expectOne('/api/auth/verify').flush(null);

    auth.signIn('replacement-test-token').subscribe();
    const verification = http.expectOne('/api/auth/verify');
    expect(verification.request.headers.getAll('Authorization')).toEqual(['Bearer replacement-test-token']);
    expect(verification.request.body).toBeNull();
    verification.flush(null);
    expect(auth.token).toBe('replacement-test-token');
  });

  it('does not persist or restore authentication when the service is recreated after reload', () => {
    const readStorage = vi.spyOn(Storage.prototype, 'getItem');
    const writeStorage = vi.spyOn(Storage.prototype, 'setItem');
    try {
      auth.signIn('memory-only-test-token').subscribe();
      http.expectOne('/api/auth/verify').flush(null);
      expect(auth.token).toBe('memory-only-test-token');

      const reloadedAuth = TestBed.runInInjectionContext(() => new AdminAuthService());
      expect(reloadedAuth.authenticated()).toBeNull();
      expect(reloadedAuth.token).toBeNull();
      expect(readStorage).not.toHaveBeenCalled();
      expect(writeStorage).not.toHaveBeenCalled();
    } finally {
      readStorage.mockRestore();
      writeStorage.mockRestore();
    }
  });
});
