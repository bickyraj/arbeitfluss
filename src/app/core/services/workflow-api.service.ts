import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import { environment } from '../../../environments/environment';
import type { Workflow } from '../models/workflow.model';

export interface WorkflowApiResponse {
  id: string;
  name: string;
  description: string | null;
  active: boolean;
  definition?: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowCreatePayload {
  name: string;
  description?: string;
  active?: boolean;
  definition?: Record<string, unknown>;
}

export interface WebhookRegistrationResponse {
  nodeId: string;
  webhookId: string;
  httpMethod: 'POST' | 'GET' | 'PUT' | 'PATCH' | 'DELETE';
  authenticationMode: 'NONE' | 'SECRET_TOKEN';
  enabled: boolean;
  secretConfigured: boolean;
}

@Injectable({ providedIn: 'root' })
export class WorkflowApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/workflows`;

  getWorkflow(id: string): Observable<WorkflowApiResponse> {
    return this.http.get<WorkflowApiResponse>(`${this.baseUrl}/${id}`);
  }

  listWorkflows(): Observable<Workflow[]> {
    return this.http.get<WorkflowApiResponse[]>(this.baseUrl).pipe(
      map((items) => items.map((item) => this.toWorkflow(item)))
    );
  }

  createWorkflow(payload: WorkflowCreatePayload): Observable<Workflow> {
    return this.http.post<WorkflowApiResponse>(this.baseUrl, payload).pipe(
      map((item) => this.toWorkflow(item))
    );
  }

  updateWorkflow(id: string, payload: Partial<WorkflowCreatePayload>): Observable<Workflow> {
    return this.http.put<WorkflowApiResponse>(`${this.baseUrl}/${id}`, payload).pipe(
      map((item) => this.toWorkflow(item))
    );
  }

  deleteWorkflow(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  getWebhooks(id: string): Observable<WebhookRegistrationResponse[]> {
    return this.http.get<WebhookRegistrationResponse[]>(`${this.baseUrl}/${id}/webhooks`);
  }

  regenerateWebhookSecret(workflowId: string, nodeId: string): Observable<{ secret: string }> {
    return this.http.post<{ secret: string }>(
      `${this.baseUrl}/${workflowId}/webhooks/${encodeURIComponent(nodeId)}/secret`,
      {}
    );
  }

  private toWorkflow(item: WorkflowApiResponse): Workflow {
    const updatedAt = item.updatedAt ?? item.createdAt ?? new Date().toISOString();
    const lastUpdated = new Date(updatedAt).toISOString();

    return {
      id: item.id,
      name: item.name,
      description: item.description ?? 'No description provided.',
      status: item.active ? 'Active' : 'Inactive',
      lastUpdated,
      lastExecution: item.active ? 'Last run' : 'Not run yet',
      executionCount: 0,
      successRate: 0,
      createdBy: 'FlowForge'
    };
  }
}
