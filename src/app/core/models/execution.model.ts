export type ExecutionStatus = 'QUEUED' | 'RUNNING' | 'CANCELLING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type ExecutionTriggerType = 'MANUAL' | 'WEBHOOK';
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
  triggerType: ExecutionTriggerType;
  status: ExecutionStatus;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  errorMessage: string | null;
  createdAt: string;
  errorCode: string | null;
  timeoutSeconds: number;
  retryOfExecutionId: string | null;
}

export interface ExecutionPage {
  content: ExecutionResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
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

/** Safe visualization projection of the immutable execution definition, without configuration. */
export interface ExecutionGraph {
  nodes: ExecutionGraphNode[];
  connections: ExecutionGraphConnection[];
}

export interface ExecutionGraphNode {
  id: string;
  type: string;
  name: string;
  position: { x: number; y: number } | null;
}

export interface ExecutionGraphConnection {
  id: string;
  sourceNodeId: string;
  sourceHandle: string;
  targetNodeId: string;
  targetHandle: string;
}
