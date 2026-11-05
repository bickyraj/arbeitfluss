import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import type {
  ExecutionGraph,
  ExecutionLogResponse,
  ExecutionPage,
  ExecutionResponse,
  ExecutionStatus,
  ExecutionStartResponse,
  NodeExecutionResponse
} from '../models/execution.model';

@Injectable({ providedIn: 'root' })
export class ExecutionApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/executions`;

  startExecution(
    workflowId: string,
    triggerInput: unknown,
    timeoutSeconds?: number
  ): Observable<ExecutionStartResponse> {
    return this.http.post<ExecutionStartResponse>(
      `${environment.apiBaseUrl}/workflows/${encodeURIComponent(workflowId)}/executions`,
      { triggerInput, ...(timeoutSeconds == null ? {} : { timeoutSeconds }) }
    );
  }

  listExecutions(
    page: number,
    size: number,
    search: string,
    status: ExecutionStatus | null,
    sort: string
  ): Observable<ExecutionPage> {
    let params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('sort', sort);
    if (search.trim()) params = params.set('search', search.trim());
    if (status) params = params.set('status', status);
    return this.http.get<ExecutionPage>(this.baseUrl, { params });
  }

  cancelExecution(executionId: string): Observable<ExecutionResponse> {
    return this.http.post<ExecutionResponse>(
      `${this.baseUrl}/${encodeURIComponent(executionId)}/cancel`,
      {}
    );
  }

  retryExecution(executionId: string): Observable<ExecutionStartResponse> {
    return this.http.post<ExecutionStartResponse>(
      `${this.baseUrl}/${encodeURIComponent(executionId)}/retry`,
      {}
    );
  }

  getExecution(executionId: string): Observable<ExecutionResponse> {
    return this.http.get<ExecutionResponse>(`${this.baseUrl}/${encodeURIComponent(executionId)}`);
  }

  getExecutionGraph(executionId: string): Observable<ExecutionGraph> {
    return this.http.get<ExecutionGraph>(`${this.baseUrl}/${encodeURIComponent(executionId)}/graph`);
  }

  getNodeExecutions(executionId: string): Observable<NodeExecutionResponse[]> {
    return this.http.get<NodeExecutionResponse[]>(`${this.baseUrl}/${encodeURIComponent(executionId)}/nodes`);
  }

  getExecutionLogs(executionId: string): Observable<ExecutionLogResponse[]> {
    return this.http.get<ExecutionLogResponse[]>(`${this.baseUrl}/${encodeURIComponent(executionId)}/logs`);
  }
}
