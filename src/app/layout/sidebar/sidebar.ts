import { Component, input, output } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import {
  Activity,
  AlertCircle,
  FileText,
  FolderKanban,
  LayoutGrid,
  LucideAngularModule,
  Menu,
  Settings,
  X,
  type LucideIconData
} from 'lucide-angular';

export interface SidebarItem {
  label: string;
  route: string;
  icon: LucideIconData;
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, LucideAngularModule],
  templateUrl: './sidebar.html',
  host: { class: 'contents' }
})
export class SidebarComponent {
  items = input.required<SidebarItem[]>();
  mobileOpen = input(false);
  closeMobile = output<void>();

  protected readonly activityIcon = Activity;
  protected readonly alertIcon = AlertCircle;
  protected readonly fileTextIcon = FileText;
  protected readonly folderIcon = FolderKanban;
  protected readonly layoutIcon = LayoutGrid;
  protected readonly settingsIcon = Settings;
  protected readonly menuIcon = Menu;
  protected readonly closeIcon = X;

  protected close(): void {
    this.closeMobile.emit();
  }
}
