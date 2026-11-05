import { Routes } from '@angular/router';
import { DashboardComponent } from './features/dashboard/dashboard';
import { WorkflowEditorComponent } from './features/workflows/workflow-editor/workflow-editor';
import { WorkflowsPageComponent } from './features/workflows/workflows-page';
import { ExecutionsPageComponent } from './features/executions/executions-page';
import { ExecutionInspectionPageComponent } from './features/executions/execution-inspection-page';
import { TemplatesPageComponent } from './features/templates/templates-page';
import { SettingsPageComponent } from './features/settings/settings-page';
import { LoginPageComponent } from './features/auth/login-page';
import { adminAuthGuard } from './core/guards/admin-auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginPageComponent },
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent, canActivate: [adminAuthGuard] },
  { path: 'workflows', component: WorkflowsPageComponent, canActivate: [adminAuthGuard] },
  { path: 'workflows/new/editor', component: WorkflowEditorComponent, canActivate: [adminAuthGuard] },
  { path: 'workflows/:id/editor', component: WorkflowEditorComponent, canActivate: [adminAuthGuard] },
  { path: 'executions', component: ExecutionsPageComponent, canActivate: [adminAuthGuard] },
  { path: 'executions/:executionId', component: ExecutionInspectionPageComponent, canActivate: [adminAuthGuard] },
  { path: 'templates', component: TemplatesPageComponent, canActivate: [adminAuthGuard] },
  { path: 'settings', component: SettingsPageComponent, canActivate: [adminAuthGuard] },
  { path: '**', redirectTo: 'dashboard' }
];
