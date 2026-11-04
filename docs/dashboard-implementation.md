# FlowForge — Dashboard Implementation

## 1. Objective

Implement the first frontend milestone for FlowForge: a modern, responsive dashboard.

The dashboard is the main landing page after opening the application.

Follow the permanent project rules in `.github/copilot-instructions.md`.

Before implementation, inspect the existing Angular project and reuse its current configuration.

## 2. Dashboard layout

Build the dashboard with three main areas:

1. Sidebar navigation.
2. Top navigation bar.
3. Main dashboard content.

The layout should feel like a professional workflow automation developer tool.

## 3. Sidebar navigation

Create a reusable sidebar component.

### Branding
- FlowForge logo using a suitable Lucide icon.
- FlowForge product name.

### Navigation items
- Overview
- Workflows
- Executions
- Templates
- Settings

### Requirements
- Clearly highlight the active navigation item.
- Use consistent icons.
- Support Angular Router navigation.
- Add hover states.
- Make the sidebar responsive.
- On smaller screens, allow it to collapse or open as a mobile navigation drawer.

## 4. Top navigation bar

Create a reusable topbar component.

Include:
- Current page title or breadcrumb.
- Search input.
- Notification icon with an indicator.
- User avatar.
- Profile menu.

Requirements:
- Clean and compact layout.
- Consistent spacing.
- Responsive behavior.
- Functional search input and profile menu interactions where practical.

## 5. Main dashboard

### Welcome section

Display:

**Good morning, [User]!**

Description:

"Here's what's happening with your workflows today."

Add a primary button:

**+ Create Workflow**

For now, clicking this button can navigate to a placeholder workflow creation page or show a clearly designed coming-soon state. Do not implement the workflow editor yet.

### Statistics cards

Create four reusable statistic cards:

**Card 1 — Total Workflows**
- Example value: 24
- Icon: workflow

**Card 2 — Active Workflows**
- Example value: 18
- Icon: activity

**Card 3 — Successful Executions**
- Example value: 1,284
- Icon: check-circle

**Card 4 — Failed Executions**
- Example value: 12
- Icon: alert-circle

Requirements:
- Display a clear title and large numerical value.
- Include a relevant icon.
- Use subtle borders and shadows.
- Make cards responsive.
- Use mock data.

### Recent Workflows section

Create a reusable table component.

Columns:
- Workflow name
- Status
- Last execution
- Success rate
- Actions

Example mock workflows:

| Workflow | Status | Last execution | Success rate |
|---|---|---|---|
| Daily Report Generator | Active | 5 minutes ago | 98% |
| Customer Data Sync | Active | 12 minutes ago | 100% |
| Email Notifications | Paused | Yesterday | 96% |
| CRM Integration | Active | 2 hours ago | 99% |
| Website Monitoring | Failed | 3 hours ago | 82% |

Requirements:
- Use status badges with distinct but subtle colors.
- Include a three-dot actions menu.
- Add a "View all workflows" link.
- Ensure the table works on smaller screens, using horizontal scrolling or an appropriate responsive layout.

### Recent Activity section

Display a timeline or activity list showing recent workflow events.

Example events:

- Daily Report Generator completed successfully.
- Customer Data Sync started.
- Email Notifications was paused.
- CRM Integration completed successfully.
- Website Monitoring execution failed.

Each activity should include:
- Event icon.
- Workflow name.
- Short event description.
- Relative timestamp.
- Appropriate status indicator.

## 6. Visual design

Follow the FlowForge design system.

### Colors
- Sidebar: `#171923`
- Workspace background: `#F8FAFC`
- Primary accent: `#6366F1`
- Cards: `#FFFFFF`
- Borders: subtle neutral gray.
- Main text: dark charcoal.
- Secondary text: muted gray.

### Styling
- Modern SaaS dashboard aesthetic.
- Clean typography.
- Consistent spacing.
- Rounded cards.
- Subtle shadows.
- Clear section hierarchy.
- Consistent icon sizes.
- Restrained use of accent colors.

Avoid excessive gradients, large decorative illustrations, and unnecessary animations.

## 7. Component architecture

Use reusable Angular standalone components.

Suggested components:

- `MainLayoutComponent`
- `SidebarComponent`
- `TopbarComponent`
- `DashboardComponent`
- `StatCardComponent`
- `RecentWorkflowsComponent`
- `RecentActivityComponent`

Adapt component names and file naming to the existing project conventions.

Keep mock data and TypeScript interfaces organized separately where appropriate.

## 8. Routing

Configure the dashboard as the default route.

Suggested routes:

- `/dashboard`
- `/workflows`
- `/executions`
- `/templates`
- `/settings`

For routes not yet implemented, create simple placeholder pages or a clear coming-soon state.

Do not implement the full functionality of these pages in this milestone.

## 9. Technical requirements

- Angular 21.
- TypeScript.
- Standalone components.
- Angular Router.
- Tailwind CSS v4.
- Lucide icons.
- Strong typing.
- Reusable components.
- Realistic mock data.
- Responsive layouts.
- No backend integration.
- No unnecessary dependencies.
- Do not introduce Angular Material or another large UI framework.

## 10. Validation

After implementation:

1. Run the Angular build.
2. Fix all TypeScript and template errors.
3. Check routing.
4. Check responsive layouts.
5. Verify that buttons and menus work.
6. Verify that the dashboard loads without console errors.

## 11. Completion report

When finished, report:

- Files created.
- Files modified.
- Components implemented.
- Routes configured.
- Dependencies added.
- Build result.
- Any remaining limitations.

Do not start implementing the next roadmap milestone until explicitly requested.
