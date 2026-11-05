# FlowForge — Milestone 8: Backend Foundation

## Objective

Build the initial FlowForge backend using Java, Spring Boot, Gradle, and PostgreSQL.

Connect the existing Angular frontend to the backend for real workflow persistence.

This milestone establishes a clean foundation for future workflow execution, AI integrations, and multi-agent orchestration.

## 1. Inspect the Existing Project

Before making changes:

- Read `.github/copilot-instructions.md`.
- Inspect the existing Angular application and project structure.
- Preserve all completed frontend features and styling.
- Keep the backend completely separate from Angular source files.
- Create the backend inside the `backend/` directory.
- Follow the existing project conventions wherever possible.

Do not rebuild or unnecessarily modify completed frontend pages.

## 2. Technology Stack

### Backend

- Java 21 LTS.
- Spring Boot (latest stable version compatible with Java 21).
- Gradle with Kotlin DSL.
- Gradle Wrapper.
- Spring Web.
- Spring Data JPA.
- Spring Validation.
- PostgreSQL.
- Flyway database migrations.
- Spring Boot Actuator.
- JUnit 5 and Spring Boot Test.

Use compatible, stable dependency versions.

### Frontend

- Existing Angular 21 application.
- TypeScript.
- Tailwind CSS v4.
- Existing routing and shared components.

### Database

- PostgreSQL.
- Docker Compose for local development.

## 3. Project Structure

Keep the existing Angular project in the root directory.

Create this structure:

```text
arbeitfluss/
├── src/                         # Existing Angular frontend
├── public/
├── docs/
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/flowforge/
│   │   │   │   ├── config/
│   │   │   │   ├── controller/
│   │   │   │   ├── dto/
│   │   │   │   ├── entity/
│   │   │   │   ├── exception/
│   │   │   │   ├── repository/
│   │   │   │   └── service/
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       └── db/migration/
│   │   └── test/
│   ├── gradle/
│   ├── gradlew
│   ├── gradlew.bat
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── docker-compose.yml
│   └── Dockerfile
└── ...
```

The exact package structure can be adjusted if needed, but maintain clear separation of responsibilities.

## 4. Gradle Configuration

Use Gradle Kotlin DSL.

Requirements:

- Create `build.gradle.kts`.
- Create `settings.gradle.kts`.
- Include the Gradle Wrapper.
- Configure Java 21 toolchain.
- Configure Spring Boot and dependency management plugins.
- Configure dependencies for the selected Spring Boot features.
- Add test dependencies.
- Use Gradle dependency management conventions.
- Do not use Maven.
- Do not create a `pom.xml`.

All backend build and test commands must use the Gradle Wrapper.

Examples:

```bash
cd backend

./gradlew build
./gradlew test
./gradlew bootRun
```

For Windows, use `gradlew.bat`.

## 5. Database Configuration

Configure PostgreSQL.

Requirements:

- Provide Docker Compose configuration.
- Use environment variables for database connection settings.
- Do not hardcode passwords.
- Configure Spring Boot PostgreSQL connectivity.
- Use Flyway for schema migrations.
- Use PostgreSQL JSONB for workflow definitions where appropriate.
- Avoid automatic Hibernate schema creation in production.

Provide a safe `.env.example` without actual credentials.

## 6. Initial Workflow Data Model

Create a Workflow entity.

Suggested fields:

- id: UUID
- name: String
- description: String, nullable
- active: Boolean
- definition: JSON-compatible workflow definition
- createdAt: timestamp
- updatedAt: timestamp

Use PostgreSQL JSONB for the workflow definition.

The definition must be flexible enough to store future visual editor data:

- Nodes.
- Node positions.
- Connections.
- Node configurations.
- Workflow metadata.

Use DTOs for API requests and responses.

Do not expose JPA entities directly through controllers.

Add appropriate database indexes and constraints.

## 7. Workflow REST API

Implement the following endpoints.

### Create workflow

`POST /api/workflows`

Creates and stores a workflow.

### List workflows

`GET /api/workflows`

Returns available workflows.

Support pagination if practical.

### Get workflow

`GET /api/workflows/{id}`

Returns a specific workflow.

### Update workflow

`PUT /api/workflows/{id}`

Updates an existing workflow.

### Delete workflow

`DELETE /api/workflows/{id}`

Deletes a workflow.

Requirements:

- Validate incoming requests.
- Use appropriate HTTP status codes.
- Return consistent JSON responses.
- Handle missing workflow IDs.
- Keep business logic inside service classes.
- Use repositories for database operations.

## 8. Health Endpoint

Implement:

`GET /api/health`

Return a simple backend health response.

Also configure Spring Boot Actuator health checks, including database health.

## 9. Error Handling

Implement centralized exception handling using `@RestControllerAdvice`.

Handle:

- Invalid requests.
- Validation failures.
- Missing resources.
- Database-related failures.
- Unexpected server errors.

Use a consistent JSON error response.

Never expose stack traces, database credentials, or internal sensitive information.

## 10. Angular Integration

Connect the existing Angular application to the backend.

Requirements:

- Create an Angular workflow API service.
- Use environment configuration for the API base URL.
- Load workflows from the backend.
- Create workflows through the API.
- Update workflows through the API.
- Delete workflows through the API.
- Add loading, success, and error states.
- Preserve existing UI and shared design components.
- Keep unrelated mock data in other sections.

Configure development CORS or an Angular development proxy.

Do not expose backend credentials to Angular.

Do not rewrite the existing frontend architecture.

## 11. Testing

Add backend tests covering:

- Workflow creation.
- Workflow listing.
- Workflow retrieval.
- Workflow updates.
- Workflow deletion.
- Invalid requests.
- Missing workflow IDs.

Use appropriate Spring Boot testing tools.

Verify database integration using a suitable test database strategy, preferably PostgreSQL-compatible integration testing.

Run:

```bash
./gradlew test
./gradlew build
```

## 12. Security and Configuration

Requirements:

- Use environment variables for sensitive configuration.
- Keep secrets out of source control.
- Provide `.env.example`.
- Do not expose database credentials to the frontend.
- Do not implement authentication yet.
- Structure the backend so authentication can be added later.
- Configure CORS explicitly for local development.

## 13. Scope Restrictions

Do not implement these features yet:

- Workflow execution engine.
- Redis.
- Rust execution service.
- OpenAI integration.
- Anthropic or Gemini integration.
- AI Agent nodes.
- Multi-agent orchestration.
- Authentication and user management.

These will be implemented in future milestones.

## 14. Documentation

Create a backend README explaining:

- Prerequisites.
- Java version.
- Gradle commands.
- PostgreSQL setup.
- Environment configuration.
- How to start the backend.
- How to run tests.
- Available API endpoints.
- How to start the Angular frontend.

## 15. Definition of Done

- Spring Boot backend starts successfully.
- Gradle Wrapper builds the backend.
- PostgreSQL starts through Docker Compose.
- Flyway creates the database schema.
- Workflow CRUD endpoints work.
- Backend tests pass.
- Angular retrieves and manages workflows through the API.
- Existing frontend functionality remains intact.
- No secrets are committed.
- Setup documentation is complete.

The result should be a clean and maintainable backend foundation for the future FlowForge execution engine and AI agent platform.

## Administrator API authentication

Management routes under `/api/**` use one shared deployment administrator token.
Generate a high-entropy value locally with `openssl rand -hex 32`, then set
`FLOWFORGE_ADMIN_TOKEN` in the shell that starts the backend:

```sh
export FLOWFORGE_ADMIN_TOKEN="$(openssl rand -hex 32)"
cd backend
./gradlew bootRun
```

Keep the value private and outside source control. Clients send
`Authorization: Bearer <administrator-token>` on management requests.
`POST /api/auth/verify` uses the same header and returns `204` when valid, `401`
when credentials are missing or invalid, and `503` when the deployment token is
missing or blank. Protected management APIs also fail closed with `503` when
unconfigured. Authentication errors never return the token.

Only `POST /api/auth/verify` and the exact reserved inbound shape
`POST /api/webhooks/{webhookId}` bypass management authentication; verification
still validates the bearer header itself. Webhook IDs contain 1–64 URL-safe
letters, digits, underscores or hyphens. Nested webhook paths and other methods
require administrator authentication. `OPTIONS` requests pass through to the
existing CORS/MVC policy without authentication; this does not grant new origins
access. Health/actuator routes outside `/api/**` retain their existing behavior.

The future browser login will hold the token in memory only and require sign-in
again after refresh. This is a shared administrator identity, not multi-user
authentication: there are no accounts, sessions, roles, individual revocation,
or per-user audit identity. Use HTTPS outside local development. No token is
stored in PostgreSQL or delivered in frontend configuration.
