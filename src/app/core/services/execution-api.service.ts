import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import type {
  ExecutionLogResponse,
  ExecutionResponse,
  ExecutionStartResponse,
  NodeExecutionResponse
} from '../models/execution.model';

@Injectable({ providedIn: 'root' })
export class ExecutionApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/executions`;

  startExecution(workflowId: string, triggerInput: unknown): Observable<ExecutionStartResponse> {
    return this.http.post<ExecutionStartResponse>(
      `${environment.apiBaseUrl}/workflows/${encodeURIComponent(workflowId)}/executions`,
      { triggerInput }
    );
  }

  getExecution(executionId: string): Observable<ExecutionResponse> {
    return this.http.get<ExecutionResponse>(`${this.baseUrl}/${encodeURIComponent(executionId)}`);
  }

  getNodeExecutions(executionId: string): Observable<NodeExecutionResponse[]> {
    return this.http.get<NodeExecutionResponse[]>(`${this.baseUrl}/${encodeURIComponent(executionId)}/nodes`);
  }

  getExecutionLogs(executionId: string): Observable<ExecutionLogResponse[]> {
    return this.http.get<ExecutionLogResponse[]>(`${this.baseUrl}/${encodeURIComponent(executionId)}/logs`);
  }
}
