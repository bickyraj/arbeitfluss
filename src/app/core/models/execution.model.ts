export type ExecutionStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type NodeExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
export type ExecutionLogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export interface ExecutionStartResponse {
  id: string;
  status: ExecutionStatus;
  createdAt: string;
}

export interface ExecutionResponse {
  id: string;
  workflowId: string;
  workflowName: string;
  triggerType: string;
  status: ExecutionStatus;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface NodeExecutionResponse {
  id: string;
  executionId: string;
  workflowNodeId: string;
  nodeType: string;
  status: NodeExecutionStatus;
  inputData: unknown;
  outputData: unknown;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface ExecutionLogResponse {
  id: string;
  executionId: string;
  nodeExecutionId: string | null;
  level: ExecutionLogLevel;
  message: string;
  details: unknown;
  createdAt: string;
}
