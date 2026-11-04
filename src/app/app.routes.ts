import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard';
import { WorkflowEditorComponent } from './features/workflows/workflow-editor/workflow-editor';
import { WorkflowsPageComponent } from './features/workflows/workflows-page';
import { ExecutionsPageComponent } from './features/executions/executions-page';
import { TemplatesPageComponent } from './features/templates/templates-page';
import { SettingsPageComponent } from './features/settings/settings-page';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent },
  { path: 'workflows', component: WorkflowsPageComponent },
  { path: 'workflows/new/editor', component: WorkflowEditorComponent },
  { path: 'workflows/:id/editor', component: WorkflowEditorComponent },
  { path: 'executions', component: ExecutionsPageComponent },
  { path: 'templates', component: TemplatesPageComponent },
  { path: 'settings', component: SettingsPageComponent },
  { path: '**', redirectTo: 'dashboard' }
];
