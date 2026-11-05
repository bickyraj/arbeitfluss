import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';

import { environment } from '../../../environments/environment';
import { AdminAuthService } from './admin-auth.service';

export const adminAuthInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(AdminAuthService).token;
  const apiUrl = `${environment.apiBaseUrl}/`;
  const isManagementApi = request.url.startsWith(apiUrl)
    && !request.url.startsWith(`${environment.apiBaseUrl}/auth/verify`)
    && !request.url.startsWith(`${environment.apiBaseUrl}/webhooks/`);

  if (!token || !isManagementApi) {
    return next(request);
  }

  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
