import { Component, computed, effect, signal } from '@angular/core';
import { Bell, Check, Globe, KeyRound, Lock, Moon, Palette, ShieldCheck, SunMedium, UserRound, Workflow, Zap, LucideAngularModule, type LucideIconData } from 'lucide-angular';
import { MainLayoutComponent } from '../../layout/main-layout/main-layout';

interface SettingsState {
  profile: {
    fullName: string;
    email: string;
    role: string;
    timezone: string;
  };
  general: {
    locale: string;
    dateFormat: string;
    compactMode: boolean;
    showTips: boolean;
  };
  appearance: {
    theme: 'light' | 'dark' | 'system';
  };
  notifications: {
    workflowRuns: boolean;
    failures: boolean;
    summaryEmails: boolean;
    desktopAlerts: boolean;
  };
  workflowPrefs: {
    autoSave: boolean;
    confirmDelete: boolean;
    retryFailedRuns: boolean;
    allowParallelRuns: boolean;
    defaultExecutionMode: 'manual' | 'scheduled';
  };
  security: {
    mfa: boolean;
    sso: boolean;
    auditLog: boolean;
    sessionTimeout: string;
  };
}

const STORAGE_KEY = 'flowforge-settings';

const defaultSettings: SettingsState = {
  profile: {
    fullName: 'Ariana Reed',
    email: 'ariana@flowforge.io',
    role: 'Operations Lead',
    timezone: 'UTC-05:00'
  },
  general: {
    locale: 'en-US',
    dateFormat: 'MM/DD/YYYY',
    compactMode: false,
    showTips: true
  },
  appearance: {
    theme: 'system'
  },
  notifications: {
    workflowRuns: true,
    failures: true,
    summaryEmails: false,
    desktopAlerts: true
  },
  workflowPrefs: {
    autoSave: true,
    confirmDelete: true,
    retryFailedRuns: false,
    allowParallelRuns: true,
    defaultExecutionMode: 'manual'
  },
  security: {
    mfa: true,
    sso: false,
    auditLog: true,
    sessionTimeout: '8 hours'
  }
};

const loadSettings = (): SettingsState => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return defaultSettings;
    }

    const parsed = JSON.parse(raw) as Partial<SettingsState>;
    return {
      ...defaultSettings,
      ...parsed,
      profile: { ...defaultSettings.profile, ...parsed.profile },
      general: { ...defaultSettings.general, ...parsed.general },
      appearance: { ...defaultSettings.appearance, ...parsed.appearance },
      notifications: { ...defaultSettings.notifications, ...parsed.notifications },
      workflowPrefs: { ...defaultSettings.workflowPrefs, ...parsed.workflowPrefs },
      security: { ...defaultSettings.security, ...parsed.security }
    };
  } catch {
    return defaultSettings;
  }
};

@Component({
  selector: 'app-settings-page',
  standalone: true,
  imports: [MainLayoutComponent, LucideAngularModule],
  template: `
    <app-main-layout [title]="'Settings'">
      <div class="space-y-6">
        <header class="flex flex-col gap-4 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6 xl:flex-row xl:items-center xl:justify-between">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.16em] text-slate-500">Workspace</p>
            <h1 class="mt-2 text-2xl font-semibold tracking-tight text-slate-900">Settings</h1>
            <p class="mt-1 text-sm text-slate-600">Manage your profile, behavior, alerts, and workflow defaults.</p>
          </div>

          <div class="flex items-center gap-3">
            <span class="rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700">
              {{ saveStatus() }}
            </span>
            <button type="button" class="rounded-xl border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50" (click)="resetSettings()">Reset</button>
            <button type="button" class="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-indigo-500" (click)="saveSettings()">Save changes</button>
          </div>
        </header>

        <div [class]="pageThemeClass()" class="space-y-6">
          <section class="grid gap-6 xl:grid-cols-[1.2fr_1fr]">
            <div class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
              <div class="mb-5 flex items-center gap-3">
                <span class="flex h-11 w-11 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                  <lucide-icon [img]="profileIcon" class="h-5 w-5" />
                </span>
                <div>
                  <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Profile</p>
                  <h2 class="text-lg font-semibold text-slate-900">Account details</h2>
                </div>
              </div>

              <div class="grid gap-4 md:grid-cols-2">
                <label class="block text-sm font-medium text-slate-700 md:col-span-2">
                  Full name
                  <input type="text" [value]="settings().profile.fullName" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateProfileField('fullName', $event)" />
                </label>

                <label class="block text-sm font-medium text-slate-700">
                  Email
                  <input type="email" [value]="settings().profile.email" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateProfileField('email', $event)" />
                </label>

                <label class="block text-sm font-medium text-slate-700">
                  Role
                  <input type="text" [value]="settings().profile.role" class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" (input)="updateProfileField('role', $event)" />
                </label>

                <label class="block text-sm font-medium text-slate-700">
                  Timezone
                  <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="settings().profile.timezone" (change)="updateProfileField('timezone', $event)">
                    <option value="UTC-05:00">UTC-05:00 (EST)</option>
                    <option value="UTC-08:00">UTC-08:00 (PST)</option>
                    <option value="UTC+00:00">UTC+00:00 (GMT)</option>
                    <option value="UTC+01:00">UTC+01:00 (CET)</option>
                  </select>
                </label>
              </div>
            </div>

            <div class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
              <div class="mb-5 flex items-center gap-3">
                <span class="flex h-11 w-11 items-center justify-center rounded-xl bg-violet-50 text-violet-600">
                  <lucide-icon [img]="paletteIcon" class="h-5 w-5" />
                </span>
                <div>
                  <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Appearance</p>
                  <h2 class="text-lg font-semibold text-slate-900">Theme</h2>
                </div>
              </div>

              <div class="grid gap-3">
                @for (option of themeOptions(); track option.value) {
                  <button type="button" class="flex items-center justify-between rounded-2xl border px-3 py-3 text-left transition" [class.border-indigo-300]="settings().appearance.theme === option.value" [class.bg-indigo-50]="settings().appearance.theme === option.value" [class.border-slate-200]="settings().appearance.theme !== option.value" [class.bg-white]="settings().appearance.theme !== option.value" (click)="setTheme(option.value)">
                    <div class="flex items-center gap-3">
                      <span class="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-100 text-slate-700">
                        <lucide-icon [img]="option.icon" class="h-4 w-4" />
                      </span>
                      <div>
                        <p class="text-sm font-medium text-slate-800">{{ option.label }}</p>
                        <p class="text-xs text-slate-500">{{ option.description }}</p>
                      </div>
                    </div>
                    @if (settings().appearance.theme === option.value) {
                      <span class="flex h-5 w-5 items-center justify-center rounded-full bg-indigo-600 text-white">
                        <lucide-icon [img]="checkIcon" class="h-3 w-3" />
                      </span>
                    }
                  </button>
                }
              </div>
            </div>
          </section>

          <section class="grid gap-6 xl:grid-cols-2">
            <div class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
              <div class="mb-5 flex items-center gap-3">
                <span class="flex h-11 w-11 items-center justify-center rounded-xl bg-amber-50 text-amber-600">
                  <lucide-icon [img]="globeIcon" class="h-5 w-5" />
                </span>
                <div>
                  <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">General</p>
                  <h2 class="text-lg font-semibold text-slate-900">Preferences</h2>
                </div>
              </div>

              <div class="space-y-4">
                <label class="block text-sm font-medium text-slate-700">
                  Locale
                  <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="settings().general.locale" (change)="updateGeneralField('locale', $event)">
                    <option value="en-US">English (US)</option>
                    <option value="en-GB">English (UK)</option>
                    <option value="de-DE">Deutsch</option>
                    <option value="fr-FR">Français</option>
                  </select>
                </label>

                <label class="block text-sm font-medium text-slate-700">
                  Date format
                  <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="settings().general.dateFormat" (change)="updateGeneralField('dateFormat', $event)">
                    <option value="MM/DD/YYYY">MM/DD/YYYY</option>
                    <option value="DD/MM/YYYY">DD/MM/YYYY</option>
                    <option value="YYYY-MM-DD">YYYY-MM-DD</option>
                  </select>
                </label>

                <div class="space-y-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                  <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                    <span>Compact mode</span>
                    <input type="checkbox" [checked]="settings().general.compactMode" (change)="toggleGeneral('compactMode', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                  </label>

                  <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                    <span>Show onboarding tips</span>
                    <input type="checkbox" [checked]="settings().general.showTips" (change)="toggleGeneral('showTips', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                  </label>
                </div>
              </div>
            </div>

            <div class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
              <div class="mb-5 flex items-center gap-3">
                <span class="flex h-11 w-11 items-center justify-center rounded-xl bg-sky-50 text-sky-600">
                  <lucide-icon [img]="bellIcon" class="h-5 w-5" />
                </span>
                <div>
                  <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Alerts</p>
                  <h2 class="text-lg font-semibold text-slate-900">Notifications</h2>
                </div>
              </div>

              <div class="space-y-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Workflow run updates</span>
                  <input type="checkbox" [checked]="settings().notifications.workflowRuns" (change)="toggleNotification('workflowRuns', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Failure alerts</span>
                  <input type="checkbox" [checked]="settings().notifications.failures" (change)="toggleNotification('failures', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Weekly summary emails</span>
                  <input type="checkbox" [checked]="settings().notifications.summaryEmails" (change)="toggleNotification('summaryEmails', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Desktop alerts</span>
                  <input type="checkbox" [checked]="settings().notifications.desktopAlerts" (change)="toggleNotification('desktopAlerts', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>
              </div>
            </div>
          </section>

          <section class="grid gap-6 xl:grid-cols-[1.2fr_0.8fr]">
            <div class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
              <div class="mb-5 flex items-center gap-3">
                <span class="flex h-11 w-11 items-center justify-center rounded-xl bg-emerald-50 text-emerald-600">
                  <lucide-icon [img]="workflowIcon" class="h-5 w-5" />
                </span>
                <div>
                  <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Automation</p>
                  <h2 class="text-lg font-semibold text-slate-900">Workflow preferences</h2>
                </div>
              </div>

              <div class="space-y-4">
                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Auto-save after edits</span>
                  <input type="checkbox" [checked]="settings().workflowPrefs.autoSave" (change)="toggleWorkflowPref('autoSave', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Confirm deletes</span>
                  <input type="checkbox" [checked]="settings().workflowPrefs.confirmDelete" (change)="toggleWorkflowPref('confirmDelete', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Retry failed runs</span>
                  <input type="checkbox" [checked]="settings().workflowPrefs.retryFailedRuns" (change)="toggleWorkflowPref('retryFailedRuns', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Allow parallel execution</span>
                  <input type="checkbox" [checked]="settings().workflowPrefs.allowParallelRuns" (change)="toggleWorkflowPref('allowParallelRuns', $event)" class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="block text-sm font-medium text-slate-700">
                  Default execution mode
                  <select class="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-sm text-slate-800 outline-none focus:border-indigo-400" [value]="settings().workflowPrefs.defaultExecutionMode" (change)="updateWorkflowPref('defaultExecutionMode', $event)">
                    <option value="manual">Manual</option>
                    <option value="scheduled">Scheduled</option>
                  </select>
                </label>
              </div>
            </div>

            <div class="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
              <div class="mb-5 flex items-center gap-3">
                <span class="flex h-11 w-11 items-center justify-center rounded-xl bg-rose-50 text-rose-600">
                  <lucide-icon [img]="shieldIcon" class="h-5 w-5" />
                </span>
                <div>
                  <p class="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">Security</p>
                  <h2 class="text-lg font-semibold text-slate-900">Frontend placeholders</h2>
                </div>
              </div>

              <div class="space-y-3 rounded-2xl border border-dashed border-rose-200 bg-rose-50 p-3">
                <p class="text-xs font-medium uppercase tracking-[0.12em] text-rose-700">Mock-only security controls</p>
                <p class="text-sm text-rose-700">These toggles are UI placeholders only and do not enforce real authentication or backend policies.</p>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Multi-factor auth</span>
                  <input type="checkbox" [checked]="settings().security.mfa" disabled class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Single sign-on</span>
                  <input type="checkbox" [checked]="settings().security.sso" disabled class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="flex items-center justify-between gap-3 text-sm text-slate-700">
                  <span>Audit logs</span>
                  <input type="checkbox" [checked]="settings().security.auditLog" disabled class="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" />
                </label>

                <label class="block text-sm font-medium text-slate-700">
                  Session timeout
                  <input type="text" [value]="settings().security.sessionTimeout" disabled class="mt-1 w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-800 outline-none" />
                </label>
              </div>
            </div>
          </section>
        </div>
      </div>
    </app-main-layout>
  `,
  host: { class: 'block' }
})
export class SettingsPageComponent {
  protected readonly settings = signal<SettingsState>(loadSettings());
  protected readonly saveStatus = signal('Saved locally');

  protected readonly profileIcon = UserRound;
  protected readonly paletteIcon = Palette;
  protected readonly globeIcon = Globe;
  protected readonly bellIcon = Bell;
  protected readonly workflowIcon = Workflow;
  protected readonly shieldIcon = ShieldCheck;
  protected readonly checkIcon = Check;
  protected readonly themeOptions = computed(() => [
    { value: 'light', label: 'Light', description: 'Classic neutral workspace', icon: SunMedium },
    { value: 'dark', label: 'Dark', description: 'Reduced glare editing mode', icon: Moon },
    { value: 'system', label: 'System', description: 'Use your device preference', icon: Zap }
  ] as const);

  protected readonly pageThemeClass = computed(() => {
    switch (this.settings().appearance.theme) {
      case 'dark':
        return 'bg-slate-950 text-slate-50';
      case 'light':
      default:
        return 'bg-[#F8FAFC] text-slate-900';
    }
  });

  constructor() {
    effect(() => {
      const next = this.settings();
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      this.saveStatus.set('Saved locally');
    });
  }

  protected saveSettings(): void {
    this.saveStatus.set('Saved just now');
    localStorage.setItem(STORAGE_KEY, JSON.stringify(this.settings()));
  }

  protected resetSettings(): void {
    this.settings.set(defaultSettings);
    this.saveStatus.set('Reset');
  }

  protected setTheme(theme: 'light' | 'dark' | 'system'): void {
    this.settings.update((current) => ({ ...current, appearance: { ...current.appearance, theme } }));
  }

  protected updateProfileField(field: keyof SettingsState['profile'], event: Event): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | null;
    const value = target?.value ?? '';
    this.settings.update((current) => ({
      ...current,
      profile: {
        ...current.profile,
        [field]: value
      }
    }));
  }

  protected updateGeneralField(field: keyof SettingsState['general'], event: Event): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | null;
    const value = target?.value ?? '';
    this.settings.update((current) => ({
      ...current,
      general: {
        ...current.general,
        [field]: value
      }
    }));
  }

  protected toggleGeneral(field: keyof Pick<SettingsState['general'], 'compactMode' | 'showTips'>, event: Event): void {
    const target = event.target as HTMLInputElement | null;
    const checked = target?.checked ?? false;
    this.settings.update((current) => ({
      ...current,
      general: {
        ...current.general,
        [field]: checked
      }
    }));
  }

  protected toggleNotification(field: keyof SettingsState['notifications'], event: Event): void {
    const target = event.target as HTMLInputElement | null;
    const checked = target?.checked ?? false;
    this.settings.update((current) => ({
      ...current,
      notifications: {
        ...current.notifications,
        [field]: checked
      }
    }));
  }

  protected toggleWorkflowPref(field: keyof Pick<SettingsState['workflowPrefs'], 'autoSave' | 'confirmDelete' | 'retryFailedRuns' | 'allowParallelRuns'>, event: Event): void {
    const target = event.target as HTMLInputElement | null;
    const checked = target?.checked ?? false;
    this.settings.update((current) => ({
      ...current,
      workflowPrefs: {
        ...current.workflowPrefs,
        [field]: checked
      }
    }));
  }

  protected updateWorkflowPref(field: keyof SettingsState['workflowPrefs'], event: Event): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | null;
    const value = target?.value ?? 'manual';
    this.settings.update((current) => ({
      ...current,
      workflowPrefs: {
        ...current.workflowPrefs,
        [field]: value as SettingsState['workflowPrefs'][typeof field]
      }
    }));
  }
}
