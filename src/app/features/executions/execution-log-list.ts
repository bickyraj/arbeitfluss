import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { ExecutionLogResponse } from '../../core/models/execution.model';
import { ExecutionJsonViewComponent } from './execution-json-view';
import { inspectionText } from './inspection-data';

@Component({
  selector: 'app-execution-log-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, ExecutionJsonViewComponent],
  standalone: true,
  template: `
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <input type="search" aria-label="Search logs" placeholder="Search messages"
             class="min-w-0 flex-1 rounded-lg border border-slate-200 px-3 py-2 text-xs" [value]="search()"
             (input)="search.set($any($event.target).value); limit.set(100)" />
      <select aria-label="Filter log level"
              class="rounded-lg border border-slate-200 bg-white px-2 py-2 text-xs text-slate-600" [value]="level()"
              (change)="level.set($any($event.target).value); limit.set(100)">
        <option value="ALL">All levels</option>
        <option>TRACE</option>
        <option>DEBUG</option>
        <option>INFO</option>
        <option>WARN</option>
        <option>ERROR</option>
      </select>
      <span class="text-xs text-slate-400">{{ filtered().length }} entries</span>
    </div>
    @if (filtered().length) {
      <ol class="max-h-[32rem] space-y-2 overflow-auto pr-1" aria-label="Persisted execution logs" tabindex="0">
        @for (log of visible(); track log.id) {
          <li class="rounded-xl border border-slate-200 border-l-[3px] bg-white p-3"
              [class.border-l-rose-500]="log.level === 'ERROR'" [class.border-l-amber-500]="log.level === 'WARN'"
              [class.border-l-indigo-300]="log.level === 'INFO'">
            <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-slate-500">
              <span class="font-bold" [class.text-rose-700]="log.level === 'ERROR'"
                    [class.text-amber-700]="log.level === 'WARN'">{{ log.level }}</span>
              <time [attr.datetime]="log.createdAt">{{ log.createdAt | date: 'medium' }}</time>
              @if (log.nodeExecutionId; as nodeId) {
                <button type="button" class="break-all font-medium text-indigo-600 hover:underline"
                        (click)="nodeSelected.emit(nodeId)">{{ text(nodeLabels()[nodeId] || nodeId) }}
                </button>
              } @else {
                <span>Execution</span>
              }
            </div>
            <p class="mt-2 whitespace-pre-wrap break-words text-sm text-slate-700">{{ text(log.message) }}</p>
            @if (log.details !== null && log.details !== undefined) {
              <button type="button" class="mt-2 text-xs font-semibold text-indigo-600"
                      [attr.aria-expanded]="expanded().has(log.id)"
                      (click)="toggle(log.id)">{{ expanded().has(log.id) ? 'Hide details' : 'Show structured details' }}
              </button>
              @if (expanded().has(log.id)) {
                <div class="mt-2">
                  <app-execution-json-view label="Log details" [value]="log.details" />
                </div>
              }
            }
          </li>
        }
      </ol>
      @if (visible().length < filtered().length) {
        <button type="button"
                class="mt-3 rounded-lg border border-slate-200 px-3 py-2 text-xs font-semibold text-indigo-600"
                (click)="limit.update(more)">Load 100 more entries
        </button>
      }
    } @else {
      <p
        class="rounded-xl bg-slate-50 p-5 text-sm text-slate-500">{{ logs().length ? 'No logs match these filters.' : 'No persisted logs for this selection yet.' }}</p>
    }
  `
})
export class ExecutionLogListComponent {
  readonly logs = input<ExecutionLogResponse[]>([]);
  readonly nodeLabels = input<Record<string, string>>({});
  readonly nodeSelected = output<string>();
  protected readonly search = signal('');
  protected readonly level = signal('ALL');
  protected readonly limit = signal(100);
  protected readonly expanded = signal(new Set<string>());
  protected readonly text = inspectionText;
  protected readonly more = (count: number) => count + 100;
  protected readonly filtered = computed(() => this.logs().filter(log =>
    (this.level() === 'ALL' || log.level === this.level()) && inspectionText(log.message).toLowerCase().includes(this.search().toLowerCase())));
  protected readonly visible = computed(() => this.filtered().slice(0, this.limit()));
  protected toggle(id: string): void {
    this.expanded.update(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  }
}
