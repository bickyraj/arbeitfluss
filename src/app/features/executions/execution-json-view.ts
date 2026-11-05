import { ChangeDetectionStrategy, Component, computed, effect, input, signal } from '@angular/core';
import { inspectionValue } from './inspection-data';

@Component({
  selector: 'app-execution-json-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="overflow-hidden rounded-xl border border-slate-200">
      <div class="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 bg-slate-50 px-3 py-2">
        <span class="text-xs font-medium text-slate-600">{{ label() }} <span class="font-normal text-slate-400">· {{ summary() }}</span></span>
        <div class="flex gap-3">
          <button type="button" class="text-xs font-medium text-slate-600 hover:text-indigo-600" [attr.aria-pressed]="wrap()" (click)="wrap.set(!wrap())">{{ wrap() ? 'No wrap' : 'Wrap lines' }}</button>
          <button type="button" class="text-xs font-semibold text-indigo-600 disabled:text-slate-400" [disabled]="value() === undefined" (click)="copy()">Copy JSON</button>
        </div>
      </div>
      @if (value() === undefined) {
        <p class="p-4 text-sm text-slate-500">No persisted data available.</p>
      } @else {
        <pre class="max-h-[28rem] overflow-auto bg-slate-950 p-4 font-mono text-xs leading-6 text-slate-100" [class.whitespace-pre-wrap]="wrap()" [style.overflow-wrap]="wrap() ? 'anywhere' : null" tabindex="0" [attr.aria-label]="label() + ' formatted JSON'">{{ preview() }}</pre>
        @if (formatted().length > previewLimit && !expanded()) {
          <div class="flex flex-wrap items-center justify-between gap-2 bg-slate-50 px-3 py-2 text-xs text-slate-500">
            <span>Preview limited to 30,000 characters. Copy includes the full redacted value.</span>
            <button type="button" class="font-semibold text-indigo-600" (click)="expanded.set(true)">Show full value</button>
          </div>
        } @else if (expanded() && formatted().length > previewLimit) {
          <button type="button" class="px-3 py-2 text-xs font-semibold text-indigo-600" (click)="expanded.set(false)">Collapse preview</button>
        }
      }
    </div>
    @if (safe().redacted || safe().limited) {
      <p class="mt-2 text-xs text-amber-700">{{ safe().redacted ? 'Credential-like values are hidden in this view and in copies.' : '' }} {{ safe().limited ? 'Deep data is limited to 64 levels for safe inspection.' : '' }}</p>
    }
    @if (copyMessage()) { <p role="status" class="mt-2 text-xs text-slate-500">{{ copyMessage() }}</p> }
  `
})
export class ExecutionJsonViewComponent {
  readonly value = input<unknown>(undefined);
  readonly label = input('JSON');
  protected readonly previewLimit = 30_000;
  protected readonly expanded = signal(false);
  protected readonly wrap = signal(false);
  protected readonly copyMessage = signal<string | null>(null);
  protected readonly safe = computed(() => inspectionValue(this.value()));
  protected readonly formatted = computed(() => JSON.stringify(this.safe().value, null, 2) ?? '');
  protected readonly preview = computed(() => this.expanded() ? this.formatted() : this.formatted().slice(0, this.previewLimit));
  protected readonly summary = computed(() => {
    const value = this.value();
    const type = value === null ? 'null' : Array.isArray(value) ? `Array · ${value.length} items`
      : typeof value === 'object' ? `Object · ${Object.keys(value ?? {}).length} keys` : typeof value;
    return `${type} · ${(this.formatted().length / 1024).toFixed(1)} KB`;
  });

  constructor() {
    effect(() => { this.value(); this.copyMessage.set(null); });
  }

  protected async copy(): Promise<void> {
    if (!navigator.clipboard?.writeText) {
      this.copyMessage.set('Clipboard unavailable. Select and copy the formatted JSON.');
      return;
    }
    try {
      await navigator.clipboard.writeText(this.formatted());
      this.copyMessage.set('Redacted JSON copied.');
    } catch {
      this.copyMessage.set('Unable to access clipboard. Select and copy the formatted JSON.');
    }
  }
}
