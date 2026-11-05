import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';
import { ExecutionDetailsComponent } from './execution-details';

@Component({
  selector: 'app-execution-inspection-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MainLayoutComponent, ExecutionDetailsComponent],
  template: `
    <app-main-layout title="Execution inspection">
      <div class="mx-auto max-w-[1600px] rounded-3xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
        @if (id(); as executionId) { <app-execution-details [executionId]="executionId" [fullPage]="true" (retryStarted)="openRetry($event)" /> }
      </div>
    </app-main-layout>
  `
})
export class ExecutionInspectionPageComponent {
  private readonly router = inject(Router);
  protected readonly id = toSignal(inject(ActivatedRoute).paramMap.pipe(map(params => params.get('executionId'))), { initialValue: null });
  protected openRetry(id: string): void { void this.router.navigate(['/executions', id]); }
}
