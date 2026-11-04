export type WorkflowStatus = 'Active' | 'Paused' | 'Failed';

export interface StatCardData {
  title: string;
  value: string;
  accent: string;
}

export interface WorkflowSummary {
  name: string;
  status: WorkflowStatus;
  lastExecution: string;
  successRate: number;
}

export interface ActivityItem {
  workflow: string;
  description: string;
  time: string;
  status: WorkflowStatus;
}
