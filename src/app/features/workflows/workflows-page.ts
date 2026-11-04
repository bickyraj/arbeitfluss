import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Ellipsis, Search, Workflow as WorkflowIcon, LucideAngularModule } from 'lucide-angular';
import type { Workflow, WorkflowSortOption, WorkflowStatus, WorkflowStatusFilter, WorkflowViewMode } from '../../core/models/workflow.model';
import { WorkflowApiService } from '../../core/services/workflow-api.service';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';

const workflowSeed: Workflow[] = [
  {
    id: 'wf-1',
    name: 'Daily Report Generator',
    description: 'Compiles marketing and sales reports every morning.',
    status: 'Active',
    lastUpdated: '5 minutes ago',
    lastExecution: '5 minutes ago',
    executionCount: 182,
    successRate: 98,
    createdBy: 'Ariana Reed'
  },
  {
    id: 'wf-2',
    name: 'Customer Data Sync',
    description: 'Syncs new customer records across CRM and billing tools.',
    status: 'Active',
    lastUpdated: '12 minutes ago',
    lastExecution: '12 minutes ago',
    executionCount: 214,
    successRate: 100,
    createdBy: 'Marcus Chen'
  },
  {
    id: 'wf-3',
    name: 'Marketing Campaign Manager',
    description: 'Launches customer segmentation and email personalization flows.',
    status: 'Inactive',
    lastUpdated: '1 hour ago',
    lastExecution: 'Yesterday',
    executionCount: 96,
    successRate: 92,
    createdBy: 'Nia Patel'
  },
  {
    id: 'wf-4',
    name: 'Finance Reconciliation',
    description: 'Matches invoices and transaction totals across finance systems.',
    status: 'Active',
    lastUpdated: '2 hours ago',
    lastExecution: '2 hours ago',
    executionCount: 143,
    successRate: 99,
    createdBy: 'Caleb Morris'
  },
  {
    id: 'wf-5',
    name: 'Support Triage Queue',
    description: 'Routes incoming support issues to the correct team.',
    status: 'Inactive',
    lastUpdated: '4 hours ago',
    lastExecution: '3 hours ago',
    executionCount: 118,
    successRate: 88,
    createdBy: 'Priya Shah'
  },
  {
    id: 'wf-6',
    name: 'Inventory Sync',
    description: 'Updates stock levels between warehouse and storefront systems.',
    status: 'Active',
    lastUpdated: '6 hours ago',
    lastExecution: '1 hour ago',
    executionCount: 312,
    successRate: 97,
    createdBy: 'Luis Ortega'
  },
  {
    id: 'wf-7',
    name: 'Email Notifications',
    description: 'Delivers transactional alerts for customer events and updates.',
    status: 'Active',
    lastUpdated: '1 day ago',
    lastExecution: 'Yesterday',
    executionCount: 456,
    successRate: 96,
    createdBy: 'Ariana Reed'
  },
  {
    id: 'wf-8',
    name: 'CRM Integration',
    description: 'Keeps sales opportunities synchronized between CRM and support.',
    status: 'Inactive',
    lastUpdated: '2 days ago',
    lastExecution: '2 days ago',
    executionCount: 89,
    successRate: 91,
    createdBy: 'Ethan Brooks'
  },
  {
    id: 'wf-9',
    name: 'Website Monitoring',
    description: 'Checks uptime and external dependencies for the public site.',
    status: 'Active',
    lastUpdated: '3 days ago',
    lastExecution: '3 hours ago',
    executionCount: 272,
    successRate: 82,
    createdBy: 'Karina Li'
  },
  {
    id: 'wf-10',
    name: 'SLA Alerting',
    description: 'Alerts the operations team when service levels are at risk.',
    status: 'Inactive',
    lastUpdated: '5 days ago',
    lastExecution: 'Last week',
    executionCount: 64,
    successRate: 84,
    createdBy: 'Samir Khan'
  }
];

@Component({
  selector: 'app-workflows-page',
  standalone: true,
  imports: [MainLayoutComponent, LucideAngularModule, RouterLink],
  template: `
    <app-main-layout [title]="'Workflows'">
      <div class="space-y-6">
        <header class="flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6 xl:flex-row xl:items-center xl:justify-between">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">Automation library</p>
            <h2 class="mt-2 text-2xl font-semibold tracking-tight text-slate-900">Workflows</h2>
            <p class="mt-1 text-sm text-slate-600">Create, organize, and manage your automations.</p>
          </div>

          <button
            type="button"
            (click)="openCreateDialog()"
            class="inline-flex items-center justify-center rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2"
          >
            + Create Workflow
          </button>
        </header>

        <section class="grid gap-4 md:grid-cols-3">
          @for (stat of stats(); track stat.label) {
            <article class="rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_10px_30px_rgba(15,23,42,0.04)]">
              <div class="flex items-center justify-between gap-3">
                <div>
                  <p class="text-sm text-slate-500">{{ stat.label }}</p>
                  <p class="mt-3 text-3xl font-semibold tracking-tight text-slate-900">{{ stat.value }}</p>
                </div>
                <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                  <lucide-icon [img]="stat.icon" class="h-5 w-5" />
                </div>
              </div>
            </article>
          }
        </section>

        <section class="rounded-3xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <label class="flex w-full items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-500 xl:max-w-md">
              <lucide-icon [img]="searchIcon" class="h-4 w-4" />
              <input
                type="search"
                [value]="search()"
                class="w-full border-0 bg-transparent text-slate-700 outline-none placeholder:text-slate-400"
                placeholder="Search workflows"
                (input)="updateSearch($event)"
              />
            </label>

            <div class="flex flex-wrap items-center gap-3">
              <label class="flex items-center gap-2 text-sm text-slate-600">
                <span>Status</span>
                <select
                  class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-300"
                  [value]="statusFilter()"
                  (change)="updateStatusFilter($any($event.target).value)"
                >
                  <option value="All">All</option>
                  <option value="Active">Active</option>
                  <option value="Inactive">Inactive</option>
                </select>
              </label>

              <label class="flex items-center gap-2 text-sm text-slate-600">
                <span>Sort</span>
                <select
                  class="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-300"
                  [value]="sortBy()"
                  (change)="updateSort($any($event.target).value)"
                >
                  <option value="recently-updated">Recently updated</option>
                  <option value="name-asc">Name A–Z</option>
                  <option value="name-desc">Name Z–A</option>
                </select>
              </label>

              <div class="inline-flex rounded-xl border border-slate-200 bg-slate-50 p-1">
                <button
                  type="button"
                  class="rounded-lg px-3 py-1.5 text-sm font-medium transition"
                  [class.bg-white]="viewMode() === 'list'"
                  [class.text-slate-900]="viewMode() === 'list'"
                  [class.text-slate-500]="viewMode() !== 'list'"
                  (click)="setViewMode('list')"
                >
                  List
                </button>
                <button
                  type="button"
                  class="rounded-lg px-3 py-1.5 text-sm font-medium transition"
                  [class.bg-white]="viewMode() === 'grid'"
                  [class.text-slate-900]="viewMode() === 'grid'"
                  [class.text-slate-500]="viewMode() !== 'grid'"
                  (click)="setViewMode('grid')"
                >
                  Grid
                </button>
              </div>

              <button type="button" class="text-sm font-medium text-slate-600 hover:text-slate-900" (click)="clearFilters()">
                Clear filters
              </button>
            </div>
          </div>
        </section>

        @if (filteredWorkflows().length === 0) {
          <div class="rounded-3xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center shadow-sm">
            <p class="text-lg font-semibold text-slate-900">No workflows match your filters.</p>
            <p class="mt-2 text-sm text-slate-500">Try a different search term or reset the filters.</p>
          </div>
        } @else if (viewMode() === 'list') {
          <div class="overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm">
            <div class="overflow-x-auto">
              <table class="min-w-full table-auto text-left text-sm">
                <thead class="bg-slate-50 text-slate-600">
                  <tr>
                    <th class="px-5 py-3 font-medium">Workflow</th>
                    <th class="px-5 py-3 font-medium">Status</th>
                    <th class="px-5 py-3 font-medium">Last execution</th>
                    <th class="px-5 py-3 font-medium">Last updated</th>
                    <th class="px-5 py-3 font-medium">Success rate</th>
                    <th class="px-5 py-3 text-right font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-slate-200 text-slate-700">
                  @for (workflow of paginatedWorkflows(); track workflow.id) {
                    <tr class="transition hover:bg-slate-50">
                      <td class="px-5 py-4">
                        <div class="flex items-center gap-3">
                          <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                            <lucide-icon [img]="workflowIcon" class="h-4 w-4" />
                          </div>
                          <div>
                            <p class="font-medium text-slate-900">{{ workflow.name }}</p>
                            <p class="text-xs text-slate-500">{{ workflow.description }}</p>
                          </div>
                        </div>
                      </td>
                      <td class="px-5 py-4">
                        <span
                          class="inline-flex rounded-full px-2.5 py-1 text-xs font-medium"
                          [class.bg-emerald-50]="workflow.status === 'Active'"
                          [class.text-emerald-700]="workflow.status === 'Active'"
                          [class.bg-slate-200]="workflow.status === 'Inactive'"
                          [class.text-slate-700]="workflow.status === 'Inactive'"
                        >
                          {{ workflow.status }}
                        </span>
                      </td>
                      <td class="px-5 py-4">{{ workflow.lastExecution }}</td>
                      <td class="px-5 py-4">{{ workflow.lastUpdated }}</td>
                      <td class="px-5 py-4">{{ workflow.successRate }}%</td>
                      <td class="px-5 py-4">
                        <div class="relative flex justify-end">
                          <button
                            type="button"
                            class="inline-flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 transition hover:bg-slate-100 hover:text-slate-700"
                            aria-label="More actions"
                            (click)="toggleMenu(workflow.id)"
                          >
                            <lucide-icon [img]="ellipsisIcon" class="h-4 w-4" />
                          </button>

                          @if (menuOpenId() === workflow.id) {
                            <div class="absolute right-0 top-10 z-20 w-44 rounded-xl border border-slate-200 bg-white p-2 shadow-lg">
                              <a [routerLink]="['/workflows', workflow.id, 'editor']" class="block w-full rounded-lg px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50">Open</a>
                              <button type="button" class="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50" (click)="toggleWorkflowStatus(workflow.id)">{{ workflow.status === 'Active' ? 'Deactivate' : 'Activate' }}</button>
                              <button type="button" class="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50" (click)="duplicateWorkflow(workflow.id)">Duplicate</button>
                              <button type="button" class="w-full rounded-lg px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50" (click)="deleteWorkflow(workflow.id)">Delete</button>
                            </div>
                          }
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        } @else {
          <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            @for (workflow of paginatedWorkflows(); track workflow.id) {
              <article class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md">
                <div class="flex items-start justify-between gap-3">
                  <div class="flex items-center gap-3">
                    <div class="flex h-11 w-11 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                      <lucide-icon [img]="workflowIcon" class="h-5 w-5" />
                    </div>
                    <div>
                      <h3 class="font-semibold text-slate-900">{{ workflow.name }}</h3>
                      <p class="text-xs text-slate-500">{{ workflow.createdBy }}</p>
                    </div>
                  </div>

                  <div class="relative">
                    <button type="button" class="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-700" aria-label="Workflow actions" (click)="toggleMenu(workflow.id)">
                      <lucide-icon [img]="ellipsisIcon" class="h-4 w-4" />
                    </button>

                    @if (menuOpenId() === workflow.id) {
                      <div class="absolute right-0 top-10 z-20 w-44 rounded-xl border border-slate-200 bg-white p-2 shadow-lg">
                        <a [routerLink]="['/workflows', workflow.id, 'editor']" class="block w-full rounded-lg px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50">Open</a>
                        <button type="button" class="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50" (click)="toggleWorkflowStatus(workflow.id)">{{ workflow.status === 'Active' ? 'Deactivate' : 'Activate' }}</button>
                        <button type="button" class="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50" (click)="duplicateWorkflow(workflow.id)">Duplicate</button>
                        <button type="button" class="w-full rounded-lg px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50" (click)="deleteWorkflow(workflow.id)">Delete</button>
                      </div>
                    }
                  </div>
                </div>

                <p class="mt-4 text-sm text-slate-600">{{ workflow.description }}</p>

                <div class="mt-4 flex items-center justify-between gap-3">
                  <span
                    class="inline-flex rounded-full px-2.5 py-1 text-xs font-medium"
                    [class.bg-emerald-50]="workflow.status === 'Active'"
                    [class.text-emerald-700]="workflow.status === 'Active'"
                    [class.bg-slate-200]="workflow.status === 'Inactive'"
                    [class.text-slate-700]="workflow.status === 'Inactive'"
                  >
                    {{ workflow.status }}
                  </span>
                  <span class="text-xs text-slate-500">{{ workflow.executionCount }} runs</span>
                </div>

                <div class="mt-4 space-y-3 text-sm text-slate-600">
                  <div class="flex items-center justify-between">
                    <span>Last updated</span>
                    <span class="font-medium text-slate-700">{{ workflow.lastUpdated }}</span>
                  </div>
                  <div class="flex items-center justify-between">
                    <span>Success rate</span>
                    <span class="font-medium text-slate-700">{{ workflow.successRate }}%</span>
                  </div>
                </div>
              </article>
            }
          </div>
        }

        <div class="flex flex-col gap-3 rounded-3xl border border-slate-200 bg-white p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between">
          <p class="text-sm text-slate-600">
            Showing {{ paginatedWorkflows().length }} of {{ filteredWorkflows().length }} workflows
          </p>

          <div class="flex items-center gap-3">
            <button
              type="button"
              class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
              [disabled]="currentPage() === 1"
              (click)="previousPage()"
            >
              Previous
            </button>
            <span class="text-sm font-medium text-slate-700">Page {{ currentPage() }} of {{ pageCount() }}</span>
            <button
              type="button"
              class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
              [disabled]="currentPage() >= pageCount()"
              (click)="nextPage()"
            >
              Next
            </button>
          </div>
        </div>
      </div>

      @if (showCreateDialog()) {
        <div class="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/35 p-4">
          <div class="w-full max-w-lg rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">New workflow</p>
                <h3 class="mt-2 text-xl font-semibold text-slate-900">Create workflow</h3>
              </div>
              <button type="button" class="text-xl text-slate-500" aria-label="Close dialog" (click)="closeCreateDialog()">×</button>
            </div>

            <div class="mt-5 space-y-4">
              <label class="block text-sm font-medium text-slate-700">
                Workflow name
                <input
                  type="text"
                  [value]="newWorkflowName()"
                  class="mt-1 w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400"
                  placeholder="e.g. Customer onboarding"
                  (input)="updateNewWorkflowName($event)"
                />
              </label>

              <label class="block text-sm font-medium text-slate-700">
                Description
                <textarea
                  rows="4"
                  [value]="newWorkflowDescription()"
                  class="mt-1 w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400"
                  placeholder="Describe what this workflow does"
                  (input)="updateNewWorkflowDescription($event)"
                ></textarea>
              </label>
            </div>

            <div class="mt-6 flex justify-end gap-3">
              <button type="button" class="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-600 hover:bg-slate-50" (click)="closeCreateDialog()">
                Cancel
              </button>
              <button
                type="button"
                class="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-indigo-500 disabled:cursor-not-allowed disabled:bg-indigo-300"
                [disabled]="!newWorkflowName().trim()"
                (click)="createWorkflow()"
              >
                Create
              </button>
            </div>
          </div>
        </div>
      }

      @if (selectedWorkflow()) {
        <div class="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/35 p-4">
          <div class="w-full max-w-lg rounded-3xl border border-slate-200 bg-white p-6 shadow-xl">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">Workflow detail</p>
                <h3 class="mt-2 text-xl font-semibold text-slate-900">{{ selectedWorkflow()?.name }}</h3>
              </div>
              <button type="button" class="text-xl text-slate-500" aria-label="Close detail dialog" (click)="closeWorkflowPreview()">×</button>
            </div>

            <div class="mt-5 rounded-2xl border border-dashed border-slate-300 bg-slate-50 p-5 text-center">
              <p class="text-lg font-semibold text-slate-900">Workflow editor coming soon</p>
              <p class="mt-2 text-sm text-slate-600">
                The visual editor and execution canvas will be added in a future milestone.
              </p>
            </div>

            <div class="mt-6 flex justify-end">
              <button type="button" class="rounded-xl bg-slate-900 px-4 py-2.5 text-sm font-medium text-white hover:bg-slate-800" (click)="closeWorkflowPreview()">
                Close
              </button>
            </div>
          </div>
        </div>
      }
    </app-main-layout>
  `,
  host: { class: 'block' }
})
export class WorkflowsPageComponent {
  private readonly workflowApi = inject(WorkflowApiService);

  protected readonly workflows = signal<Workflow[]>(workflowSeed);
  protected readonly search = signal('');
  protected readonly sortBy = signal<WorkflowSortOption>('recently-updated');
  protected readonly statusFilter = signal<WorkflowStatusFilter>('All');
  protected readonly viewMode = signal<WorkflowViewMode>('list');
  protected readonly currentPage = signal(1);
  protected readonly menuOpenId = signal<string | null>(null);
  protected readonly showCreateDialog = signal(false);
  protected readonly newWorkflowName = signal('');
  protected readonly newWorkflowDescription = signal('');
  protected readonly selectedWorkflow = signal<Workflow | null>(null);

  protected readonly searchIcon = Search;
  protected readonly workflowIcon = WorkflowIcon;
  protected readonly ellipsisIcon = Ellipsis;

  protected readonly pageSize = 8;

  constructor() {
    this.loadWorkflows();
  }

  protected loadWorkflows(): void {
    this.workflowApi.listWorkflows().subscribe({
      next: (workflows) => {
        this.workflows.set(workflows.length > 0 ? workflows : workflowSeed);
      },
      error: () => {
        this.workflows.set(workflowSeed);
      }
    });
  }

  protected buildWorkflowDefinition(name: string): Record<string, unknown> {
    return {
      workflowId: `wf-${Date.now()}`,
      workflowName: name,
      nodes: [],
      connections: [],
      selectedNodeId: null,
      selectedConnectionId: null,
      isDirty: false
    };
  }

  protected readonly stats = computed(() => {
    const total = this.workflows().length;
    const active = this.workflows().filter((workflow) => workflow.status === 'Active').length;
    const inactive = total - active;

    return [
      { label: 'All Workflows', value: total.toString(), icon: WorkflowIcon },
      { label: 'Active Workflows', value: active.toString(), icon: WorkflowIcon },
      { label: 'Inactive Workflows', value: inactive.toString(), icon: Search }
    ];
  });

  protected readonly filteredWorkflows = computed(() => {
    const query = this.search().trim().toLowerCase();
    const status = this.statusFilter();
    const filtered = this.workflows().filter((workflow) => {
      const matchesText = !query || workflow.name.toLowerCase().includes(query) || workflow.description.toLowerCase().includes(query);
      const matchesStatus = status === 'All' || workflow.status === status;
      return matchesText && matchesStatus;
    });

    return [...filtered].sort((left, right) => {
      switch (this.sortBy()) {
        case 'name-asc':
          return left.name.localeCompare(right.name);
        case 'name-desc':
          return right.name.localeCompare(left.name);
        default:
          return right.lastUpdated.localeCompare(left.lastUpdated);
      }
    });
  });

  protected readonly pageCount = computed(() => {
    return Math.max(1, Math.ceil(this.filteredWorkflows().length / this.pageSize));
  });

  protected readonly paginatedWorkflows = computed(() => {
    const start = (this.currentPage() - 1) * this.pageSize;
    return this.filteredWorkflows().slice(start, start + this.pageSize);
  });

  protected updateSearch(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    this.search.set(target?.value ?? '');
    this.currentPage.set(1);
  }

  protected updateStatusFilter(value: string): void {
    this.statusFilter.set(value as WorkflowStatusFilter);
    this.currentPage.set(1);
  }

  protected updateSort(value: string): void {
    this.sortBy.set(value as WorkflowSortOption);
    this.currentPage.set(1);
  }

  protected setViewMode(mode: WorkflowViewMode): void {
    this.viewMode.set(mode);
  }

  protected clearFilters(): void {
    this.search.set('');
    this.statusFilter.set('All');
    this.sortBy.set('recently-updated');
    this.viewMode.set('list');
    this.currentPage.set(1);
  }

  protected toggleMenu(workflowId: string): void {
    this.menuOpenId.set(this.menuOpenId() === workflowId ? null : workflowId);
  }

  protected openWorkflow(workflow: Workflow): void {
    this.selectedWorkflow.set(workflow);
    this.menuOpenId.set(null);
  }

  protected closeWorkflowPreview(): void {
    this.selectedWorkflow.set(null);
  }

  protected toggleWorkflowStatus(workflowId: string): void {
    const workflow = this.workflows().find((item) => item.id === workflowId);
    if (!workflow) {
      return;
    }

    const nextStatus: WorkflowStatus = workflow.status === 'Active' ? 'Inactive' : 'Active';
    this.workflowApi
      .updateWorkflow(workflowId, {
        name: workflow.name,
        description: workflow.description,
        active: nextStatus === 'Active',
        definition: this.buildWorkflowDefinition(workflow.name)
      })
      .subscribe({
        next: (updatedWorkflow) => {
          this.workflows.update((items) => items.map((item) => (item.id === workflowId ? updatedWorkflow : item)));
          this.menuOpenId.set(null);
        },
        error: () => {
          this.menuOpenId.set(null);
        }
      });
  }

  protected duplicateWorkflow(workflowId: string): void {
    const workflow = this.workflows().find((item) => item.id === workflowId);

    if (!workflow) {
      return;
    }

    this.workflowApi
      .createWorkflow({
        name: `${workflow.name} Copy`,
        description: workflow.description,
        active: false,
        definition: this.buildWorkflowDefinition(`${workflow.name} Copy`)
      })
      .subscribe({
        next: (createdWorkflow) => {
          this.workflows.update((items) => [createdWorkflow, ...items]);
          this.menuOpenId.set(null);
        },
        error: () => {
          this.menuOpenId.set(null);
        }
      });
  }

  protected deleteWorkflow(workflowId: string): void {
    const workflow = this.workflows().find((item) => item.id === workflowId);
    if (!workflow) {
      return;
    }

    const confirmed = window.confirm(`Delete "${workflow.name}"? This action cannot be undone.`);
    if (!confirmed) {
      this.menuOpenId.set(null);
      return;
    }

    this.workflowApi.deleteWorkflow(workflowId).subscribe({
      next: () => {
        this.workflows.update((items) => items.filter((item) => item.id !== workflowId));
        this.menuOpenId.set(null);
        this.currentPage.set(Math.min(this.currentPage(), this.pageCount()));
      },
      error: () => {
        this.menuOpenId.set(null);
      }
    });
  }

  protected openCreateDialog(): void {
    this.showCreateDialog.set(true);
    this.newWorkflowName.set('');
    this.newWorkflowDescription.set('');
  }

  protected closeCreateDialog(): void {
    this.showCreateDialog.set(false);
    this.newWorkflowName.set('');
    this.newWorkflowDescription.set('');
  }

  protected updateNewWorkflowName(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    this.newWorkflowName.set(target?.value ?? '');
  }

  protected updateNewWorkflowDescription(event: Event): void {
    const target = event.target as HTMLTextAreaElement | null;
    this.newWorkflowDescription.set(target?.value ?? '');
  }

  protected createWorkflow(): void {
    const name = this.newWorkflowName().trim();
    if (!name) {
      return;
    }

    this.workflowApi
      .createWorkflow({
        name,
        description: this.newWorkflowDescription().trim() || 'A new workflow created from the management dashboard.',
        active: true,
        definition: this.buildWorkflowDefinition(name)
      })
      .subscribe({
        next: (newWorkflow) => {
          this.workflows.update((items) => [newWorkflow, ...items]);
          this.closeCreateDialog();
          this.currentPage.set(1);
        },
        error: () => {
          this.closeCreateDialog();
        }
      });
  }

  protected previousPage(): void {
    this.currentPage.update((page) => Math.max(1, page - 1));
  }

  protected nextPage(): void {
    this.currentPage.update((page) => Math.min(this.pageCount(), page + 1));
  }
}
