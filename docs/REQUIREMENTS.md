# System Requirements Specification (SRS) - AWS EventBridge Scheduler Dashboard

**Document Version:** 1.0.0  
**Project Name:** AWS EventBridge Scheduler Dashboard (`aws-scheduler-dashboard`)  
**Technology Stack:** Java 21 (Quarkus 3.8 REST API) + React 18 (TypeScript, Vite, Tailwind CSS)  
**Status:** Architecture & Specification Phase  

---

## 1. Executive Summary & Scope

### 1.1 Problem Statement
AWS EventBridge Scheduler is utilized as the cloud-native cron trigger for critical batch pipelines (such as Flink financial SMS lakehouse ingestion, automated daily/weekly/monthly financial reports, and Let's Encrypt wildcard TLS certificate renewals). While virtually free (~$0.005/month under the AWS PAID plan), the native AWS Console lacks:
1. **Execution History & Status Visibility**: No consolidated view of when a schedule fired, whether the downstream GitHub Actions workflow succeeded or failed, or how long the job took.
2. **One-Click Manual Trigger ("Run Now")**: No button to immediately fire a schedule on demand or override input parameters for ad-hoc backfills.
3. **End-to-End Topology Traceability**: Schedule definitions, EventBridge routing rules, API Destination endpoints, and target GitHub repositories are scattered across multiple AWS services.

### 1.2 Architectural Boundary (Crucial Constraint)
- **NOT a Replacement Scheduler**: This system MUST NOT run its own background cron threads or replace AWS EventBridge Scheduler. AWS EventBridge Scheduler remains the single source of truth for time-based scheduling.
- **Stateless Control Plane & Observability Portal**: The Java Quarkus backend acts purely as a low-footprint REST API aggregator and control plane that queries/mutates AWS EventBridge via AWS SDK v2 and correlates execution runs via the GitHub Actions REST API.

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
- **FR-2.1 (Downstream GitHub Run History)**: For schedules mapped to a GitHub Actions API Destination, the backend MUST query the GitHub Actions Workflow Runs API (`GET /repos/{owner}/{repo}/actions/workflows/{workflow_id}/runs`) and return recent execution records containing:
  - `runId`, `runNumber`, `status` (`queued`, `in_progress`, `completed`), and `conclusion` (`success`, `failure`, `cancelled`, `skipped`).
  - `createdAt`, `updatedAt`, computed `durationSeconds`, and `htmlUrl` (direct link to GitHub Actions UI logs).
- **FR-2.2 (Multi-Schedule Disambiguation)**: Because multiple schedules (e.g., `test-sms-flink-cron`, `sms-flink-report-daily`, `sms-flink-report-weekly`, `sms-flink-report-monthly`) can target the same GitHub workflow file (`trigger-nuc-batch-runner.yml`) with different `inputs`, the correlator MUST attribute runs to schedules using:
  1. Workflow run title / display title or job name matching when available; and/or
  2. Schedule firing window alignment (matching `createdAt` against the cron firing timestamps of each schedule).
- **FR-2.3 (AWS CloudWatch Invocation Telemetry)**: The backend MUST query AWS CloudWatch Metrics (`cloudwatch:GetMetricData`) under namespaces `AWS/Scheduler` and `AWS/Events` over the past 7 days (or 24 hours) to report:
  - Total `InvocationAttempts` / `MatchedEvents`.
  - Total `SuccessfulInvocationAttempts` vs. `FailedInvocations` (e.g., HTTP 4xx/5xx from GitHub API).

### FR-3: Manual Trigger ("Run Now") & Ad-Hoc Parameter Dispatch
- **FR-3.1 (One-Click Default Trigger via EventBridge)**: Each schedule card on the dashboard MUST provide a **"Run Now" (`立即触发`)** button. By default, clicking it invokes `POST /api/v1/schedules/{name}/trigger` with `mode=EVENTBRIDGE`, which sends the schedule's exact `Source`, `DetailType`, and `Input` payload to AWS EventBridge (`events:PutEvents`), testing the entire cloud routing chain end-to-end.
- **FR-3.2 (Custom Input Override Trigger via GitHub API)**: Operators MUST be able to open a **"Trigger with Parameters" (`带参运行`)** modal that pre-populates the target rule's JSON `inputs` (e.g., `{"target_env": "finance", "pipeline_stage": "report", "report_period": "DAILY"}`). Submitting with `mode=GITHUB_DIRECT` dispatches `POST /repos/{owner}/{repo}/actions/workflows/{workflow}/dispatches` directly with the customized inputs.
- **FR-3.3 (Post-Trigger Live Polling)**: After a manual trigger is fired, the UI MUST automatically poll/refresh the schedule's execution status after 3–5 seconds to display the newly queued/in-progress GitHub Actions run.

### FR-4: Schedule Lifecycle & Configuration Management
- **FR-4.1 (Pause / Resume Schedule)**: Operators MUST be able to toggle a schedule's state between `ENABLED` and `DISABLED` directly from the dashboard (`PATCH /api/v1/schedules/{name}/state`). The backend MUST fetch the current schedule definition and call `scheduler:UpdateSchedule` preserving all existing target, retry, and role configurations.
- **FR-4.2 (Update Schedule Expression & Timezone)**: Operators MUST be able to modify a schedule's `ScheduleExpression` (`rate(...)` or `cron(...)`), `ScheduleExpressionTimezone`, and `Description` via an edit modal (`PUT /api/v1/schedules/{name}`).

### FR-5: Executive Web Dashboard UI (`web/`)
- **FR-5.1 (KPI Summary Banner)**: Top summary bar displaying:
  - Total Managed Schedules (`Enabled` vs. `Paused`).
  - 24h / 7d Invocation Success Rate (%).
  - Currently Running / Queued Jobs count.
  - AWS Account Plan Status (`PAID` / Active) & Region badge (`ap-southeast-1`).
- **FR-5.2 (Interactive Schedule Cards / Table)**: Filterable by status (`ALL`, `ENABLED`, `DISABLED`), category (`Finance ETL`, `Infra / TLS`), or keyword search.
- **FR-5.3 (Slide-Over Execution Drawer & Topology Graph)**: Clicking a schedule opens a detailed drawer showing the visual 3-hop topology (`AWS Scheduler ➔ EventBridge Rule ➔ GitHub Workflow`), recent 20 execution runs with status badges, duration bars, and a 7-day CloudWatch invocation sparkline.

---

## 3. Non-Functional Requirements (NFR)

- **NFR-1 (Ultra-Low Memory Footprint)**: By utilizing **Java 21 + Quarkus 3.8** (without heavy ORM/database layers), the backend container MUST operate comfortably within **128MB–256MB RAM** in JVM mode (or **<64MB RAM** if compiled to GraalVM/Mandrel Native), making it ideal for scheduling on K3s edge/cloud nodes (`nuc` or `free-arm-vm`).
- **NFR-2 (Zero Database Dependency / Statelessness)**: The dashboard requires **no dedicated database** (zero MySQL/Postgres tables needed). All state lives natively in AWS EventBridge and GitHub Actions, with short-lived in-memory caching (`15s–60s` TTL) in Quarkus to prevent GitHub/AWS API rate limiting.
- **NFR-3 (Cost Neutrality on AWS)**: CloudWatch metric queries and EventBridge API calls MUST use coarse-grained batching and caching so that dashboard usage incurs **$0.00** additional AWS billing.
- **NFR-4 (Security & Zero-Trust SSO Integration)**:
  - All AWS credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) and `GITHUB_TOKEN` MUST reside strictly in backend environment variables / Kubernetes Secrets, never exposed to the browser.
  - In production K3s (`tencent-dp1-cluster`), the dashboard route (`https://gw.jppwl.asia/scheduler`) integrates seamlessly behind Kong Gateway's **Logto OAuth2 Forward-Auth** plugin.
