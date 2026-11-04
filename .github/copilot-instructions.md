# FlowForge — Frontend UI Instructions

## 1. Project overview

FlowForge is a modern, self-hosted workflow automation platform inspired by n8n.

The frontend should provide a clean, professional interface where users can:
- View and manage workflows.
- Visually create workflows.
- Configure automation nodes.
- Monitor workflow executions.
- Browse reusable workflow templates.
- Manage application settings.

Focus exclusively on frontend development.

## 2. Technology stack

- Angular 21
- TypeScript
- Angular standalone components
- Angular Router
- Tailwind CSS v4
- Lucide icons (`lucide-angular`)

Use the existing project configuration. Do not recreate the Angular project.

## 3. UI design system

### Brand identity
- Product name: FlowForge
- Primary color: Indigo `#6366F1`
- Sidebar background: `#171923`
- Main workspace background: `#F8FAFC`
- Card background: `#FFFFFF`
- Borders: Subtle neutral gray

### Design direction
- Modern professional SaaS dashboard.
- Developer-tool aesthetic.
- Clean and minimal layouts.
- Consistent spacing and typography.
- Rounded cards and subtle shadows.
- Clear visual hierarchy.
- Responsive desktop, tablet, and mobile layouts.
- Accessible colors and interactive controls.

Avoid:
- Excessive gradients.
- Unnecessary animations.
- Overly decorative elements.
- Cluttered interfaces.
- Inconsistent spacing and styling.

## 4. Angular development standards

- Use standalone components.
- Use TypeScript with strict typing.
- Follow Angular best practices.
- Use Angular Router for navigation.
- Organize code by feature and responsibility.
- Prefer reusable UI components.
- Keep components small and maintainable.
- Separate presentation from data and business logic.
- Use signals where appropriate.
- Avoid unnecessary dependencies.

## 5. Suggested frontend structure

Adapt this structure to the existing project:

```text
src/app/
├── core/
│   ├── models/
│   └── services/
├── layout/
│   ├── sidebar/
│   ├── topbar/
│   └── main-layout/
├── shared/
│   └── components/
├── features/
│   ├── dashboard/
│   ├── workflows/
│   ├── executions/
│   ├── templates/
│   └── settings/
├── app.ts
├── app.html
└── app.routes.ts
```

## 6. Styling standards

- Use Tailwind CSS v4.
- Keep styling consistent across all pages.
- Prefer Tailwind utility classes.
- Extract repeated UI patterns into reusable components.
- Avoid unnecessary custom CSS.
- Use responsive utility classes.
- Maintain consistent spacing, colors, borders, and typography.

## 7. Frontend functionality

- Use realistic mock data during initial UI development.
- Make buttons, menus, navigation, filters, and interactions functional where practical.
- Do not create fake backend integrations.
- Keep mock data separate from presentation components.
- Design components so real API integration can be added later.

## 8. Development workflow

- Work incrementally, one UI feature at a time.
- Inspect existing files before modifying them.
- Do not overwrite working code unnecessarily.
- Preserve existing project configuration.
- Build reusable components rather than duplicating code.
- Run the Angular build after significant changes.
- Fix all build errors before considering a task complete.

## 9. UI implementation roadmap

Implement these features in order:

1. Application layout and dashboard.
2. Workflow listing and management.
3. Visual drag-and-drop workflow editor.
4. Node library and configuration panels.
5. Execution history and logs.
6. Templates gallery.
7. Settings pages.
8. Responsive and accessibility improvements.

Only implement the feature explicitly requested. Do not jump ahead to later milestones.
