import { Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Activity, AlertCircle, CheckCircle2, Workflow as WorkflowIcon } from 'lucide-angular';
import type { ActivityItem, WorkflowSummary } from '../../core/models/dashboard.model';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';
import { RecentActivityComponent } from './components/recent-activity';
import { RecentWorkflowsComponent } from './components/recent-workflows';
import { StatCardComponent } from './components/stat-card';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, MainLayoutComponent, StatCardComponent, RecentWorkflowsComponent, RecentActivityComponent],
  template: `
    <app-main-layout [title]="'Overview'">
      <section class="space-y-6">
        <div class="flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:flex-row sm:items-center sm:justify-between sm:p-6">
          <div>
            <p class="text-sm font-medium text-slate-500">Good morning, Ariana!</p>
            <h2 class="mt-1 text-2xl font-semibold tracking-tight text-slate-900">Here's what's happening with your workflows today.</h2>
          </div>

          <a
            routerLink="/workflows"
            class="inline-flex items-center justify-center rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2"
          >
            + Create Workflow
          </a>
        </div>

        <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          @for (stat of stats(); track stat.title) {
            <app-stat-card [title]="stat.title" [value]="stat.value" [icon]="stat.icon" />
          }
        </div>

        <div class="grid gap-6 xl:grid-cols-[1.7fr_1fr]">
          <app-recent-workflows [workflows]="workflowRows" />
          <app-recent-activity [activities]="activityItems" />
        </div>
      </section>
    </app-main-layout>
  `,
  host: { class: 'block' }
})
export class DashboardComponent {
  protected readonly stats = signal([
    { title: 'Total Workflows', value: '24', icon: WorkflowIcon },
    { title: 'Active Workflows', value: '18', icon: Activity },
    { title: 'Successful Executions', value: '1,284', icon: CheckCircle2 },
    { title: 'Failed Executions', value: '12', icon: AlertCircle }
  ]);

  protected readonly workflowRows: WorkflowSummary[] = [
    { name: 'Daily Report Generator', status: 'Active', lastExecution: '5 minutes ago', successRate: 98 },
    { name: 'Customer Data Sync', status: 'Active', lastExecution: '12 minutes ago', successRate: 100 },
    { name: 'Email Notifications', status: 'Paused', lastExecution: 'Yesterday', successRate: 96 },
    { name: 'CRM Integration', status: 'Active', lastExecution: '2 hours ago', successRate: 99 },
    { name: 'Website Monitoring', status: 'Failed', lastExecution: '3 hours ago', successRate: 82 }
  ];

  protected readonly activityItems: ActivityItem[] = [
    { workflow: 'Daily Report Generator', description: 'completed successfully', time: '5 min ago', status: 'Active' },
    { workflow: 'Customer Data Sync', description: 'started', time: '12 min ago', status: 'Active' },
    { workflow: 'Email Notifications', description: 'was paused', time: '1 hour ago', status: 'Paused' },
    { workflow: 'CRM Integration', description: 'completed successfully', time: '2 hours ago', status: 'Active' },
    { workflow: 'Website Monitoring', description: 'execution failed', time: '3 hours ago', status: 'Failed' }
  ];
}
