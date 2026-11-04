export type WorkflowNodeType =
  | 'manual-trigger'
  | 'schedule-trigger'
  | 'webhook-trigger'
  | 'http-request'
  | 'send-email'
  | 'database-query'
  | 'log-message'
  | 'if-condition'
  | 'switch'
  | 'merge'
  | 'set-data'
  | 'transform-data'
  | 'filter-data';

export type WorkflowNodeCategory = 'Triggers' | 'Actions' | 'Logic' | 'Data';

export type WorkflowNodeConfiguration = Record<string, string | number | boolean | undefined>;

export interface WorkflowNode {
  id: string;
  type: WorkflowNodeType;
  name: string;
  description: string;
  position: {
    x: number;
    y: number;
  };
  configuration: WorkflowNodeConfiguration;
}

export interface WorkflowConnection {
  id: string;
  sourceNodeId: string;
  sourceHandle: string;
  targetNodeId: string;
  targetHandle: string;
}

export interface WorkflowEditorState {
  workflowId: string;
  workflowName: string;
  workflowDescription?: string;
  nodes: WorkflowNode[];
  connections: WorkflowConnection[];
  selectedNodeId: string | null;
  selectedConnectionId: string | null;
  isDirty: boolean;
}

export interface NodeLibraryItem {
  id: WorkflowNodeType;
  name: string;
  description: string;
  category: WorkflowNodeCategory;
}
