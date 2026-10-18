# System Requirements Specification (SRS) - AWS EventBridge Scheduler Dashboard

**Document Version:** 1.1.0  
**Project Name:** AWS EventBridge Scheduler Dashboard (`aws-scheduler-dashboard`)  
**Technology Stack:** Java 21 (Quarkus 3.8 GraalVM/Mandrel Native REST API) + React 18 (TypeScript, Vite, Tailwind CSS, Nginx Alpine)  
**Gateway & Auth:** Cloudflare Same-Origin (`gw.jppwl.asia`) + Kong (`oauth2-forward-auth`) + OAuth2-Proxy + Logto (GitHub SSO)  
**Status:** Architecture & Specification Phase  

---

## 1. Executive Summary & Scope

### 1.1 Problem Statement
AWS EventBridge Scheduler is utilized as the cloud-native cron trigger for critical batch pipelines (such as Flink financial SMS lakehouse ingestion, automated daily/weekly/monthly financial reports, and Let's Encrypt wildcard TLS certificate renewals). While virtually free (~$0.005/month under the AWS PAID plan), the native AWS Console lacks:
1. **Execution History & Status Visibility**: No consolidated view of when a schedule fired, whether the downstream GitHub Actions workflow succeeded or failed, or how long the job took.
2. **One-Click Manual Trigger ("Run Now")**: No button to immediately fire a schedule on demand or override input parameters for ad-hoc backfills.
3. **End-to-End Topology Traceability**: Schedule definitions, EventBridge routing rules, API Destination endpoints, and target GitHub repositories are scattered across multiple AWS services.

### 1.2 Architectural Boundary (Crucial Constraints)
- **NOT a Replacement Scheduler**: This system MUST NOT run its own background cron threads or replace AWS EventBridge Scheduler. AWS EventBridge Scheduler remains the single source of truth for time-based scheduling.
- **No "Create Schedule" UI (Manage Existing Only)**: Creating brand-new schedules, EventBridge rules, and API destinations involves multi-resource IAM & webhook wiring and remains managed via IaC / AWS CLI. The dashboard focuses strictly on **observability, manual triggering (`Run Now`), pause/resume toggling, and modifying cron/timezone expressions of existing schedules** (`scheduler:CreateSchedule` is explicitly out of scope).
- **Stateless Control Plane & Observability Portal**: The Java Quarkus Native backend acts purely as a low-footprint REST API aggregator and control plane that queries/mutates AWS EventBridge via AWS SDK v2 and correlates execution runs via the GitHub Actions REST API.

---

## 2. Functional Requirements (FR)

### FR-1: Schedule Inventory & End-to-End Topology Discovery
- **FR-1.1 (Schedule Listing)**: The backend MUST fetch all schedules within the configured AWS EventBridge Scheduler group (default: `default` in `ap-southeast-1`) via `scheduler:ListSchedules` and `scheduler:GetSchedule`.
- **FR-1.2 (Cron & Next Fire Calculation)**: For each schedule, the system MUST parse both `rate(...)` and `cron(...)` expressions alongside `ScheduleExpressionTimezone` (e.g., `Asia/Shanghai`, `UTC`) to compute and return:
  - A human-readable schedule summary.
  - The exact timestamp of the **Next Scheduled Execution (`nextFireTime`)** and countdown timer.
- **FR-1.3 (Multi-Hop Routing Resolution)**: When a schedule targets an EventBridge EventBus (`arn:aws:events:...:event-bus/default`), the backend MUST inspect `EventBridgeParameters` (`Source` and `DetailType`), match them against active EventBridge Rules (`events:ListRules`), and resolve the downstream `ApiDestination` (`events:ListTargetsByRule` & `events:ListApiDestinations`) to extract:
  - Target GitHub Owner & Repository (e.g., `nvd11/sms-flink-etl`).
  - Target Workflow Filename (e.g., `trigger-nuc-batch-runner.yml`).
  - Target Branch (`ref`) and static JSON `inputs` configured on the rule target.

### FR-2: Unified Execution History & Telemetry Correlation
- **FR-2.1 (API Responsibility Boundary — AWS vs. GitHub)**:
  - **AWS APIs (`scheduler:*`, `events:*`)** are the single source of truth for **listing scheduled jobs**, their state (`ENABLED`/`DISABLED`), cron expressions, and target routing topology.
  - Because AWS EventBridge Scheduler is a stateless "fire-and-forget" trigger that provides zero native execution history logs or downstream job status, **GitHub Actions REST API (`GET /repos/{owner}/{repo}/actions/workflows/{workflow_id}/runs`)** MUST be queried to retrieve **historical execution records, runtime duration, and step log links**.
  - Each execution record returned to the UI MUST include: `runId`, `runNumber`, `displayTitle`, `status` (`queued`, `in_progress`, `completed`), `conclusion` (`success`, `failure`, `cancelled`, `skipped`), `createdAt`, `updatedAt`, computed `durationSeconds`, `attributionMethod` (`DISPLAY_TITLE` or `CRON_WINDOW`), and `htmlUrl` (direct link to GitHub Actions UI logs).
- **FR-2.4 (In-App Run Step Breakdown & Plain-Text Console Log Viewer)**:
  - Operators MUST be able to click any historical run record in the dashboard to inspect its execution steps and console output directly in an embedded Terminal Log Viewer (`GET /scheduler/api/v1/schedules/{name}/runs/{runId}/logs`) without leaving the dashboard.
  - To protect the `<64MB` GraalVM Native memory footprint, the backend MUST query GitHub's per-job plain-text log endpoint (`GET /repos/{owner}/{repo}/actions/jobs/{jobId}/logs` alongside `/actions/runs/{runId}/jobs` for step statuses) using a bounded line buffer (`tailLines=500`, max `128KB`), avoiding multi-megabyte ZIP archive decompression in memory.
- **FR-2.2 (Multi-Schedule Disambiguation Strategy — Dual-Layer Attribution)**:
  - Because multiple schedules (`test-sms-flink-cron`, `sms-flink-report-daily`, `sms-flink-report-weekly`, `sms-flink-report-monthly`) dispatch to the **same** GitHub workflow file (`trigger-nuc-batch-runner.yml`) with different `inputs`, `ExecutionCorrelatorService` MUST implement a **two-tier attribution strategy**:
    1. **Tier 1 — Primary Exact Match via GitHub Workflow `run-name` (`display_title`)**:
       - Target GitHub workflows configure a dynamic `run-name` incorporating the dispatched inputs (e.g., `run-name: "Batch ETL [${{ inputs.pipeline_stage || 'ingest' }} - ${{ inputs.report_period || 'INCREMENTAL' }}]"`).
       - The correlator inspects each run's `display_title` from the GitHub API response and matches it against the schedule's injected `defaultInputs` (e.g., `report_period=DAILY` ➔ `sms-flink-report-daily`, `report_period=WEEKLY` ➔ `sms-flink-report-weekly`, `report_period=MONTHLY` ➔ `sms-flink-report-monthly`, or `ingest`/`INCREMENTAL` ➔ `test-sms-flink-cron`). This guarantees **100% accuracy** even for ad-hoc manual triggers (`Run Now`).
    2. **Tier 2 — Fallback Match via Cron Firing Window Alignment**:
       - For legacy historical runs recorded before `run-name` was added (where `display_title` is generic), the correlator computes the previous firing timestamps of each candidate schedule via `AwsCronEvaluator` and attributes the run to the schedule whose firing timestamp aligns closest with the run's `created_at` (within a configurable tolerance window, e.g., `±180 seconds`).
- **FR-2.3 (AWS CloudWatch Invocation Telemetry)**: The backend MUST query AWS CloudWatch Metrics (`cloudwatch:GetMetricData`) under namespaces `AWS/Scheduler` and `AWS/Events` over the past 7 days (or 24 hours) to report:
  - Total `InvocationAttempts` / `MatchedEvents`.
  - Total `SuccessfulInvocationAttempts` vs. `FailedInvocations` (e.g., HTTP 4xx/5xx from GitHub API).

### FR-3: Manual Trigger ("Run Now") & Ad-Hoc Parameter Dispatch
- **FR-3.1 (One-Click Default Trigger via EventBridge)**: Each schedule card on the dashboard MUST provide a **"Run Now" (`立即触发`)** button. By default, clicking it invokes `POST /api/v1/schedules/{name}/trigger` with `mode=EVENTBRIDGE`, which sends the schedule's exact `Source`, `DetailType`, and `Input` payload to AWS EventBridge (`events:PutEvents`), testing the entire cloud routing chain end-to-end.
- **FR-3.2 (Custom Input Override Trigger via GitHub API)**: Operators MUST be able to open a **"Trigger with Parameters" (`带参运行`)** modal that pre-populates the target rule's JSON `inputs` (e.g., `{"target_env": "finance", "pipeline_stage": "report", "report_period": "DAILY"}`). Submitting with `mode=GITHUB_DIRECT` dispatches `POST /repos/{owner}/{repo}/actions/workflows/{workflow}/dispatches` directly with the customized inputs.
- **FR-3.3 (Post-Trigger Live Polling)**: After a manual trigger is fired, the UI MUST automatically poll/refresh the schedule's execution status after 3–5 seconds to display the newly queued/in-progress GitHub Actions run.

### FR-4: Schedule Lifecycle & Configuration Management (Existing Schedules Only)
- **FR-4.1 (Pause / Resume Schedule)**: Operators MUST be able to toggle an existing schedule's state between `ENABLED` and `DISABLED` directly from the dashboard (`PATCH /scheduler/api/v1/schedules/{name}/state`). The backend MUST fetch the current schedule definition and call `scheduler:UpdateSchedule` preserving all existing target, retry, and role configurations.
- **FR-4.2 (Update Schedule Expression & Timezone)**: Operators MUST be able to modify an existing schedule's `ScheduleExpression` (`rate(...)` or `cron(...)`), `ScheduleExpressionTimezone`, and `Description` via an edit modal (`PUT /scheduler/api/v1/schedules/{name}`).
- **FR-4.3 (No Schedule Creation / Deletion)**: The system MUST NOT expose endpoints for creating or deleting schedules.

### FR-5: Executive Web Dashboard UI (`web/`)
- **FR-5.1 (KPI Summary Banner & SSO Identity Header)**:
  - Top navigation bar displaying the authenticated GitHub user's avatar (`https://github.com/{username}.png`), username, email (fetched via `GET /scheduler/api/auth/me`), and a one-click SSO Logout button (`/oauth2/sign_out?rd=/scheduler/`).
  - KPI summary bar displaying:
    - Total Managed Schedules (`Enabled` vs. `Paused`).
    - 24h / 7d Invocation Success Rate (%).
    - Currently Running / Queued Jobs count.
    - AWS Account Plan Status (`PAID` / Active) & Region badge (`ap-southeast-1`).
- **FR-5.2 (Interactive Schedule Cards / Table)**: Filterable by status (`ALL`, `ENABLED`, `DISABLED`), category (`Finance ETL`, `Infra / TLS`), or keyword search.
- **FR-5.3 (Slide-Over Execution Drawer & Topology Graph)**: Clicking a schedule opens a detailed drawer showing the visual 3-hop topology (`AWS Scheduler ➔ EventBridge Rule ➔ GitHub Workflow`), recent 20 execution runs with status badges, duration bars, and a 7-day CloudWatch invocation sparkline.

---

## 3. Non-Functional Requirements (NFR)

- **NFR-1 (GraalVM ARM64 Native Compilation & Ultra-Low Memory Footprint on `radxa-cubie-a7a`)**:
  - Because the service targets the **`radxa-cubie-a7a` (`linux/arm64`)** K3s edge node where memory is precious, `scheduler-backend` MUST be compiled into a **native `linux/arm64` (`aarch64`) GraalVM / Mandrel executable** (`-Dnative` on `ubuntu-24.04-arm`).
  - It MUST use zero-reflection or explicit `@RegisterForReflection` DTOs, a lightweight AWS SDK HTTP client (`url-connection-client`), and a custom reflection-free `AwsCronEvaluator`, ensuring startup in **<50ms** and runtime RSS memory within **35MB–64MB RAM** (`-Xmx48m`).
- **NFR-2 (Zero Database Dependency / Statelessness)**: The dashboard requires **no dedicated database** (zero MySQL/Postgres tables needed). All state lives natively in AWS EventBridge and GitHub Actions, with short-lived in-memory caching (`15s–60s` TTL) in Quarkus to prevent GitHub/AWS API rate limiting.
- **NFR-3 (Cost Neutrality on AWS)**: CloudWatch metric queries and EventBridge API calls MUST use coarse-grained batching and caching so that dashboard usage incurs **$0.00** additional AWS billing.
- **NFR-4 (Astra-Aligned Security, Cloudflare Same-Origin & Kong Forward-Auth SSO)**:
  - **Dual-Pod Split Architecture**: Deployed identically to `astra` as two independent ArgoCD applications (`scheduler-frontend` serving the Vite SPA via Nginx Alpine, and `scheduler-backend` running the Quarkus Native binary) using `my-shared-helm-charts/charts/generic-web-service-v2`.
  - **Cloudflare Same-Origin Routing**: Both frontend (`/scheduler`) and backend (`/scheduler/api` with `konghq.com/strip-path: "true"`) are exposed under the same Cloudflare-proxied domain (`https://gw.jppwl.asia` and `https://gw-tx.jppwl.asia`), eliminating CORS issues.
  - **Kong + Logto + GitHub SSO (`oauth2-forward-auth`)**: Authentication is enforced at the Kong Gateway edge via OAuth2-Proxy and Logto (GitHub OAuth). Kong strips spoofed headers and injects `X-Auth-Request-User`, `X-Auth-Request-Preferred-Username`, `X-Auth-Request-Email`, and `X-Auth-Request-Access-Token` into backend requests. No user whitelist is enforced initially (any valid Logto-authenticated session is permitted), while `APP_AUTH_ENABLED=false` allows seamless local development.
  - **Zero-Plaintext Secret Management via OCI Vault `ExternalSecret`**: Aligned with `higress-dashboard-backend`, sensitive credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `GITHUB_TOKEN`) MUST NEVER be committed to Git manifests. They are stored in **OCI Vault** and dynamically synced into Kubernetes Secret `scheduler-backend-secrets` via External Secrets Operator (`externalSecret.enabled: true` in `generic-web-service-v2`).
- **NFR-5 (Automated Unit & Integration Testing with ≥80% Code Coverage Gate)**:
  - **Backend (`backend/`)**: MUST include comprehensive **JUnit 5 + RestAssured + Mockito (`@QuarkusTest`)** test suites covering all REST endpoints, 3-hop topology correlation, dual-layer run attribution, `AwsCronEvaluator`, and Forward-Auth security. **JaCoCo** (`jacoco-maven-plugin`) MUST enforce a minimum **80% line & instruction coverage gate** (`mvn test jacoco:report jacoco:check`).
  - **Frontend (`web/`)**: MUST include comprehensive **Jest + React Testing Library (`@testing-library/react`)** test suites covering API services, utility formatters, and all core UI components (`ScheduleCard`, `RunHistoryAndLogDrawer`, `TerminalLogViewer`, `TriggerModal`, `CronEditModal`, `TopNavbar`, `KpiSummaryBanner`). Jest's `coverageThreshold` MUST enforce **≥80% statements/lines/functions/branches coverage** (`npm test -- --coverage`).
