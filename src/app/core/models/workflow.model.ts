export type WorkflowStatus = 'Active' | 'Inactive';
export type WorkflowSortOption = 'recently-updated' | 'name-asc' | 'name-desc';
export type WorkflowViewMode = 'list' | 'grid';
export type WorkflowStatusFilter = 'All' | WorkflowStatus;

export interface Workflow {
  id: string;
  name: string;
  description: string;
  status: WorkflowStatus;
  lastUpdated: string;
  lastExecution: string;
  executionCount: number;
  successRate: number;
  createdBy: string;
}
