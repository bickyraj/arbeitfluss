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

export type WorkflowScalarValue = string | number | boolean | null;
export type WorkflowNodeConfiguration = Record<string, WorkflowScalarValue | undefined>;

export type ConditionalOperator =
  | 'equals'
  | 'not equals'
  | 'greater than'
  | 'greater than or equal'
  | 'less than'
  | 'less than or equal'
  | 'is empty'
  | 'is not empty';

export type ConditionalOutputHandle = 'true' | 'false';

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
