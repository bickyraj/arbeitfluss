import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class AdminAuthService {
  private readonly http = inject(HttpClient);
  private readonly tokenState = signal<string | null>(null);

  readonly authenticated = this.tokenState.asReadonly();

  signIn(token: string): Observable<void> {
    return this.http.post<void>(`${environment.apiBaseUrl}/auth/verify`, null, {
      headers: { Authorization: `Bearer ${token}` }
    }).pipe(
      tap(() => this.tokenState.set(token))
    );
  }

  signOut(): void {
    this.tokenState.set(null);
  }

  get token(): string | null {
    return this.tokenState();
  }
}
