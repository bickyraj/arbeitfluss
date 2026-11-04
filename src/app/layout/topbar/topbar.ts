import { Component, input, output, signal } from '@angular/core';
import { Bell, ChevronDown, LucideAngularModule, Menu, Search, UserRound } from 'lucide-angular';

@Component({
  selector: 'app-topbar',
  standalone: true,
  imports: [LucideAngularModule],
  templateUrl: './topbar.html',
  host: { class: 'block' }
})
export class TopbarComponent {
  title = input.required<string>();
  toggleSidebar = output<void>();

  protected readonly searchIcon = Search;
  protected readonly bellIcon = Bell;
  protected readonly menuIcon = Menu;
  protected readonly chevronDownIcon = ChevronDown;
  protected readonly userIcon = UserRound;
  protected readonly searchValue = signal('');
  protected readonly profileMenuOpen = signal(false);

  protected updateSearch(event: Event): void {
    const target = event.target as HTMLInputElement | null;
    this.searchValue.set(target?.value ?? '');
  }

  protected toggleMenu(): void {
    this.profileMenuOpen.update((value) => !value);
  }

  protected openSidebar(): void {
    this.toggleSidebar.emit();
  }
}
