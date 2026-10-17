# Backend REST API Development Specification (`scheduler-backend`)

**Document Version:** 1.0.0  
**Service Name:** `scheduler-backend`  
**Runtime & Framework:** Java 21 + Quarkus 3.8 (RESTEasy Reactive / `quarkus-rest-jackson`, GraalVM / Mandrel Native Image `<64MB RAM`)  
**External Gateway Prefix:** `/scheduler/api` (Kong `strip-path: "true"` ➔ Backend receives `/...`)  
**Authentication:** Kong `oauth2-forward-auth` (`X-Auth-Request-*` headers + Logto `/oidc/me` fallback)  

---

## 1. API Design Conventions & Global Contracts

### 1.1 Path Routing Convention (Kong Strip-Path Alignment)
- **Browser / Frontend Request Path**: All frontend requests use `baseURL: '/scheduler/api'` (e.g., `GET /scheduler/api/v1/schedules`).
- **Backend Internal JAX-RS `@Path`**: Because Kong Gateway (`konghq.com/strip-path: "true"`) and Vite local dev proxy strip the `/scheduler/api` prefix before forwarding to port `8080`, all Quarkus JAX-RS resources bind directly to the stripped root path (e.g., `@Path("/v1/schedules")`, `@Path("/auth")`, `@Path("/")`).

### 1.2 Unified JSON Response Envelope (`ApiResponse<T>`)
Aligned with `astra-backend`, all business REST endpoints (`/auth/*` and `/v1/*`) return a standardized JSON envelope annotated with `@RegisterForReflection`:

```json
{
  "code": 0,
  "message": "success",
  "data": { ... },
  "timestamp": "2026-10-18T08:30:00Z"
}
```

- **Success**: HTTP `200 OK` (or `202 Accepted` for async triggers) with `code: 0`.
- **Error**: HTTP `400` / `401` / `404` / `500` / `502` with a non-zero `code` and descriptive `message`:

```json
{
  "code": 40401,
  "message": "Schedule 'unknown-job' not found in group 'default'",
  "data": null,
  "timestamp": "2026-10-18T08:30:00Z"
}
```

### 1.3 GraalVM Native & Virtual Thread Rules
1. **Zero Reflection Surprises**: Every request/response Java `record` MUST be annotated with `@io.quarkus.runtime.annotations.RegisterForReflection`.
2. **Virtual Thread Execution**: Every blocking controller method that invokes AWS SDK v2 (`url-connection-client`) or the GitHub REST Client MUST be annotated with `@io.smallrye.common.annotation.RunOnVirtualThread`.

---

## 2. Endpoint Summary Matrix

| # | Category | Browser URL | Backend `@Path` | Method | Auth Required | Cache / Side Effect |
| :-: | :--- | :--- | :--- | :---: | :---: | :--- |
| 1 | **Probes** | `/scheduler/api/live` | `/live` | `GET` | No | Liveness probe (instant `200 OK`) |
| 2 | **Probes** | `/scheduler/api/ready` | `/ready` | `GET` | No | Readiness probe (`200 OK`) |
| 3 | **Auth** | `/scheduler/api/auth/me` | `/auth/me` | `GET` | Yes* | Caffeine `300s` TTL (Logto user profile) |
| 4 | **Auth** | `/scheduler/api/auth/logout` | `/auth/logout` | `POST` | No | Returns `/oauth2/sign_out?rd=/scheduler/` |
| 5 | **System** | `/scheduler/api/v1/health` | `/v1/health` | `GET` | Yes* | Checks AWS & GitHub config readiness |
| 6 | **Schedules** | `/scheduler/api/v1/schedules` | `/v1/schedules` | `GET` | Yes* | Topology `60s` TTL + Runs `15s` TTL |
| 7 | **Schedules** | `/scheduler/api/v1/schedules/{name}` | `/v1/schedules/{name}` | `GET` | Yes* | Returns single schedule + next 5 fire times |
| 8 | **History** | `/scheduler/api/v1/schedules/{name}/runs` | `/v1/schedules/{name}/runs` | `GET` | Yes* | Dual-layer attributed runs (`15s` TTL) |
| 9 | **Logs** | `/scheduler/api/v1/schedules/{name}/runs/{runId}/logs` | `/v1/schedules/{name}/runs/{runId}/logs` | `GET` | Yes* | Fetches Job Steps + plain-text console log (`tailLines`) |
| 10 | **Control** | `/scheduler/api/v1/schedules/{name}/trigger` | `/v1/schedules/{name}/trigger` | `POST` | Yes* | Fires `PutEvents` or GitHub `dispatches` |
| 11 | **Control** | `/scheduler/api/v1/schedules/{name}/state` | `/v1/schedules/{name}/state` | `PATCH` | Yes* | Calls `scheduler:UpdateSchedule` (State) |
| 12 | **Control** | `/scheduler/api/v1/schedules/{name}` | `/v1/schedules/{name}` | `PUT` | Yes* | Calls `scheduler:UpdateSchedule` (Cron/TZ) |
| 13 | **Helper** | `/scheduler/api/v1/cron/preview` | `/v1/cron/preview` | `POST` | Yes* | Validates Cron/Rate & previews next 5 fire times |

> *\*Note: When `APP_AUTH_ENABLED=false` (local development / unit tests), authentication checks are bypassed and a mock user (`nvd11`) is injected automatically.*

---

## 3. Detailed Endpoint Specifications

### 3.1 Kubernetes Health Probes (`HealthResource`)

#### `GET /live` & `GET /ready`
Lightweight endpoints used by K3s `kubelet` liveness and readiness probes (configured in `generic-web-service-v2`). Must NOT make external AWS/GitHub network calls.

- **Response (`200 OK`)**:
```json
{
  "status": "UP",
  "service": "scheduler-backend",
  "nativeMode": true
}
```

---

### 3.2 Authentication & Identity Endpoints (`AuthResource`)

#### 3.2.1 `GET /auth/me` — Get Current Authenticated User
Resolves the current operator's identity from Kong Forward-Auth headers (aligned 1:1 with `astra-backend`).

- **Request Headers (Injected by Kong `oauth2-forward-auth` in Production)**:
  - `X-Auth-Request-User`: Logto user `sub` ID (e.g., `zhspkt3s6lm2`)
  - `X-Auth-Request-Preferred-Username`: GitHub username (e.g., `nvd11`)
  - `X-Auth-Request-Email`: User email (e.g., `nvd11@163.com`)
  - `X-Auth-Request-Access-Token`: OIDC access token (optional)
- **Backend Resolution Logic**:
  1. If `APP_AUTH_ENABLED=false`, immediately return mock user (`id: "local-dev"`, `username: "nvd11"`, `email: "nvd11@163.com"`, `avatarUrl: "https://github.com/nvd11.png"`).
  2. If `APP_AUTH_ENABLED=true`, read `X-Auth-Request-User`. If missing, return HTTP `401 Unauthorized`.
  3. Extract `username` from `X-Auth-Request-Preferred-Username`. If `username` equals `sub` and `X-Auth-Request-Access-Token` is present, query `${APP_LOGTO_ENDPOINT}/oidc/me` (cached in Caffeine for `300s` by `sub`) to extract `identities.github.details.login` and `avatar_url`.
  4. Fallback `avatarUrl` to `https://github.com/{username}.png`.
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "zhspkt3s6lm2",
    "username": "nvd11",
    "email": "nvd11@163.com",
    "avatarUrl": "https://github.com/nvd11.png",
    "authMode": "forward-auth"
  },
  "timestamp": "2026-10-18T08:30:00Z"
}
```
- **Error Response (`401 Unauthorized`)**:
```json
{
  "code": 40100,
  "message": "Missing SSO identity headers (X-Auth-Request-User)",
  "data": null,
  "timestamp": "2026-10-18T08:30:00Z"
}
```

#### 3.2.2 `POST /auth/logout` — SSO Sign-Out URL
Returns the Kong OAuth2-Proxy sign-out endpoint to clear the `.jppwl.asia` root cookie.

- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "signoutUrl": "/oauth2/sign_out?rd=/scheduler/"
  },
  "timestamp": "2026-10-18T08:30:00Z"
}
```

---

### 3.3 System Overview & Connectivity (`SchedulerResource`)

#### 3.3.1 `GET /v1/health` — Control Plane Status & Cloud Metadata
Used by the dashboard top banner to display AWS account/region metadata and verify backend connectivity.

- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "HEALTHY",
    "awsRegion": "ap-southeast-1",
    "awsAccountId": "186004631963",
    "schedulerGroup": "default",
    "eventBusName": "default",
    "githubConfigured": true,
    "cacheStats": {
      "topologyTtlSeconds": 60,
      "runHistoryTtlSeconds": 15
    }
  },
  "timestamp": "2026-10-18T08:30:00Z"
}
```

---

### 3.4 Schedule Inventory & Topology Discovery (`SchedulerResource`)

#### 3.4.1 `GET /v1/schedules` — List All Schedules with Enriched Topology & Telemetry
Returns the full inventory of schedules in the configured group (`default`), enriched with 3-hop EventBridge/GitHub routing metadata, 7-day CloudWatch metrics, KPI summary counts, and the latest attributed GitHub Actions run.

- **Query Parameters**:
  - `refresh` (`boolean`, optional, default `false`): If `true`, evicts both the topology cache and run history cache before fetching fresh data from AWS and GitHub.
  - `state` (`string`, optional): Filter by `ENABLED` or `DISABLED`.
  - `q` (`string`, optional): Case-insensitive substring filter matching `name`, `description`, `source`, or `githubRepo`.
- **Backend Execution Steps (`@RunOnVirtualThread`)**:
  1. Fetch or load cached 3-hop topology from `AwsTopologyService` (`ListSchedules` + parallel `GetSchedule` + `ListRules` + `ListTargetsByRule` + `ListApiDestinations`).
  2. Compute `humanReadableCron`, `nextFireTime`, and `nextFireInSeconds` for each schedule using `AwsCronEvaluator`.
  3. Fetch 7-day CloudWatch metrics (`InvocationAttempts`, `FailedInvocations`) via `CloudWatchService.getMetrics7d()`.
  4. Fetch recent workflow runs from GitHub (`ExecutionCorrelatorService`) and attach the most recent attributed run (`lastExecution`) to each schedule.
  5. Compute top-level KPI summary statistics (`ScheduleListResponse`).
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "summary": {
      "totalSchedules": 5,
      "enabledSchedules": 5,
      "disabledSchedules": 0,
      "runningOrQueuedJobs": 0,
      "invocations7d": 1029,
      "successRate7d": 100.0,
      "awsAccountId": "186004631963",
      "awsRegion": "ap-southeast-1"
    },
    "items": [
      {
        "name": "sms-flink-report-daily",
        "groupName": "default",
        "arn": "arn:aws:scheduler:ap-southeast-1:186004631963:schedule/default/sms-flink-report-daily",
        "description": "Nightly 23:50 (Asia/Shanghai) financial daily report trigger",
        "category": "FINANCE_REPORT",
        "state": "ENABLED",
        "scheduleExpression": "cron(50 23 * * ? *)",
        "timezone": "Asia/Shanghai",
        "humanReadableCron": "Every day at 23:50 (Asia/Shanghai)",
        "nextFireTime": "2026-10-18T15:50:00Z",
        "nextFireInSeconds": 26400,
        "lastModifiedDate": "2026-10-14T18:30:06Z",
        "routing": {
          "eventBusName": "default",
          "source": "finance.sms-flink-report",
          "detailType": "daily-report",
          "matchedRuleName": "rule-trigger-github-daily-report",
          "matchedRuleArn": "arn:aws:events:ap-southeast-1:186004631963:rule/rule-trigger-github-daily-report",
          "apiDestinationName": "github-trigger-nuc-batch",
          "invocationEndpoint": "https://api.github.com/repos/nvd11/sms-flink-etl/actions/workflows/trigger-nuc-batch-runner.yml/dispatches",
          "githubOwner": "nvd11",
          "githubRepo": "sms-flink-etl",
          "workflowFile": "trigger-nuc-batch-runner.yml",
          "targetRef": "main",
          "defaultInputs": {
            "target_env": "finance",
            "pipeline_stage": "report",
            "report_period": "DAILY"
          }
        },
        "metrics7d": {
          "invocations": 7,
          "successfulInvocations": 7,
          "failedInvocations": 0,
          "dailyBuckets": [
            { "date": "2026-10-12", "invocations": 1, "failures": 0 },
            { "date": "2026-10-13", "invocations": 1, "failures": 0 },
            { "date": "2026-10-14", "invocations": 1, "failures": 0 },
            { "date": "2026-10-15", "invocations": 1, "failures": 0 },
            { "date": "2026-10-16", "invocations": 1, "failures": 0 },
            { "date": "2026-10-17", "invocations": 1, "failures": 0 },
            { "date": "2026-10-18", "invocations": 1, "failures": 0 }
          ]
        },
        "lastExecution": {
          "runId": 1948271625,
          "runNumber": 88,
          "displayTitle": "Batch ETL [report - DAILY]",
          "attributionMethod": "DISPLAY_TITLE",
          "event": "workflow_dispatch",
          "status": "completed",
          "conclusion": "success",
          "startedAt": "2026-10-17T15:50:04Z",
          "updatedAt": "2026-10-17T15:52:19Z",
          "durationSeconds": 135,
          "htmlUrl": "https://github.com/nvd11/sms-flink-etl/actions/runs/1948271625"
        }
      }
    ]
  },
  "timestamp": "2026-10-18T08:30:00Z"
}
```

---

#### 3.4.2 `GET /v1/schedules/{name}` — Get Single Schedule Detail & Next 5 Fire Times
Returns the full configuration of a single schedule along with its next 5 computed firing timestamps (used in the Schedule Detail Drawer and Cron Editor preview).

- **Path Parameter**: `name` (e.g., `sms-flink-report-daily`)
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "schedule": { /* Same structure as SchedulePipelineView item above */ },
    "upcomingFireTimes": [
      "2026-10-18T15:50:00Z",
      "2026-10-19T15:50:00Z",
      "2026-10-20T15:50:00Z",
      "2026-10-21T15:50:00Z",
      "2026-10-22T15:50:00Z"
    ],
    "flexibleTimeWindowMode": "OFF",
    "retryPolicy": {
      "maximumEventAgeInSeconds": 86400,
      "maximumRetryAttempts": 185
    },
    "targetRoleArn": "arn:aws:iam::186004631963:role/service-role/Amazon_EventBridge_Scheduler_EVENTBRIDGE_..."
  },
  "timestamp": "2026-10-18T08:30:00Z"
}
```

---

### 3.5 Execution History & Dual-Layer Attribution (`SchedulerResource`)

#### 3.5.1 `GET /v1/schedules/{name}/runs` — List Correlated GitHub Actions Runs
Queries the downstream GitHub Actions workflow runs and filters/attributes them to the specified schedule using the **Dual-Layer Attribution Algorithm**.

- **Path Parameter**: `name` (e.g., `sms-flink-report-daily`)
- **Query Parameters**:
  - `limit` (`int`, optional, default `20`, max `50`): Maximum number of attributed runs to return.
  - `refresh` (`boolean`, optional, default `false`): Bypass the `15s` run history cache (used 4 seconds after clicking "Trigger Now").
- **Attribution Logic (`ExecutionCorrelatorService`)**:
  1. Resolve `(githubOwner, githubRepo, workflowFile)` for schedule `{name}`.
  2. Call GitHub REST API: `GET /repos/{owner}/{repo}/actions/workflows/{workflowFile}/runs?per_page=50`.
  3. If only 1 schedule in the inventory targets `{owner}/{repo}/{workflowFile}` (e.g., `jppwl-tls-cert-renew-weekly` ➔ `renew-jppwl-tls.yml`), attribute all runs directly (`attributionMethod: "SINGLE_OWNER"`).
  4. If multiple schedules share `{workflowFile}` (e.g., `trigger-nuc-batch-runner.yml` shared by 4 Flink schedules):
     - **Tier 1 (`DISPLAY_TITLE`)**: Check `run.display_title`. Match tokens against `routing.defaultInputs` (`DAILY` ➔ `sms-flink-report-daily`, `WEEKLY` ➔ `sms-flink-report-weekly`, `MONTHLY` ➔ `sms-flink-report-monthly`, `ingest` / `INCREMENTAL` ➔ `test-sms-flink-cron`).
     - **Tier 2 (`CRON_WINDOW`)**: If `display_title` does not contain disambiguating tokens, compute the most recent firing timestamp before `run.created_at` for each of the 4 schedules using `AwsCronEvaluator`. Assign the run to the schedule whose fire time satisfies `0 <= (run.created_at - fireTime) <= 180s` with minimum delta.
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "scheduleName": "sms-flink-report-daily",
    "githubRepo": "nvd11/sms-flink-etl",
    "workflowFile": "trigger-nuc-batch-runner.yml",
    "totalMatched": 14,
    "runs": [
      {
        "runId": 1948271625,
        "runNumber": 88,
        "displayTitle": "Batch ETL [report - DAILY]",
        "attributionMethod": "DISPLAY_TITLE",
        "event": "workflow_dispatch",
        "status": "completed",
        "conclusion": "success",
        "startedAt": "2026-10-17T15:50:04Z",
        "updatedAt": "2026-10-17T15:52:19Z",
        "durationSeconds": 135,
        "htmlUrl": "https://github.com/nvd11/sms-flink-etl/actions/runs/1948271625"
      }
    ]
  },
  "timestamp": "2026-10-18T08:30:00Z"
}
```

---

#### 3.5.2 `GET /v1/schedules/{name}/runs/{runId}/logs` — Get Run Job Steps & Console Log Output
Retrieves the structured job/step execution breakdown and plain-text console log output for a specific GitHub Actions workflow run so operators can inspect logs and errors directly inside the dashboard's built-in Terminal Viewer without leaving the page.

- **Path Parameters**:
  - `name`: Schedule name (e.g., `sms-flink-report-daily`)
  - `runId`: GitHub Actions workflow run ID (e.g., `1948271625`)
- **Query Parameters**:
  - `jobId` (`long`, optional): Specific GitHub job ID if the workflow contains multiple jobs. If omitted, the backend automatically selects the first failed job (`conclusion == "failure"`) or defaults to the first job in the run.
  - `tailLines` (`int`, optional, default `500`, max `2000`): Number of trailing log lines to return.
- **GraalVM Native Memory-Safe Log Streaming Logic**:
  1. Resolve `(githubOwner, githubRepo)` from schedule `{name}`.
  2. Query `GET https://api.github.com/repos/{owner}/{repo}/actions/runs/{runId}/jobs` to retrieve all jobs and their granular `steps[]` (`number`, `name`, `status`, `conclusion`, `started_at`, `completed_at`).
  3. Select target `jobId` (explicit query param ➔ first failed job ➔ first job).
  4. Query `GET https://api.github.com/repos/{owner}/{repo}/actions/jobs/{jobId}/logs` (which returns a `302 Redirect` to a **plain-text UTF-8 stream**, avoiding the memory overhead of downloading and unzipping the multi-megabyte `/runs/{runId}/logs` ZIP archive inside a `<64MB` Native container!).
  5. Stream the plain-text response using a bounded ring buffer of `tailLines` (capped at `128 KB` max payload) and return alongside the structured `steps` list.
  *(Note: If the job is still `queued` or `in_progress` and GitHub has not finalized the log blob yet, `logContent` returns a descriptive placeholder message while `steps[]` reflects live step progress.)*
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "scheduleName": "sms-flink-report-daily",
    "runId": 1948271625,
    "jobId": 3029184712,
    "jobName": "trigger-nuc-batch",
    "status": "completed",
    "conclusion": "success",
    "startedAt": "2026-10-17T15:50:04Z",
    "completedAt": "2026-10-17T15:52:19Z",
    "durationSeconds": 135,
    "htmlUrl": "https://github.com/nvd11/sms-flink-etl/actions/runs/1948271625/job/3029184712",
    "jobs": [
      {
        "jobId": 3029184712,
        "name": "trigger-nuc-batch",
        "status": "completed",
        "conclusion": "success",
        "durationSeconds": 135
      }
    ],
    "steps": [
      {
        "number": 1,
        "name": "Set up job",
        "status": "completed",
        "conclusion": "success",
        "durationSeconds": 2
      },
      {
        "number": 2,
        "name": "Trigger Flink Daily Report on NUC",
        "status": "completed",
        "conclusion": "success",
        "durationSeconds": 131
      },
      {
        "number": 3,
        "name": "Complete job",
        "status": "completed",
        "conclusion": "success",
        "durationSeconds": 2
      }
    ],
    "truncated": false,
    "totalLines": 42,
    "logContent": "2026-10-17T15:50:05.112Z ##[group]Run curl -sS -X POST ...\n2026-10-17T15:50:06.401Z [INFO] Submitting Flink SQL job: report_period=DAILY\n2026-10-17T15:52:18.902Z [SUCCESS] Daily report pushed to Slack\n"
  },
  "timestamp": "2026-10-18T08:40:00Z"
}
```

---

### 3.6 Job Control & Lifecycle Mutation Endpoints (`SchedulerResource`)

#### 3.6.1 `POST /v1/schedules/{name}/trigger` — Manual Trigger ("Run Now")
Fires an immediate execution of the schedule using either native AWS EventBridge routing (`EVENTBRIDGE`) or direct GitHub Actions workflow dispatch with custom input overrides (`GITHUB_DIRECT`).

- **Path Parameter**: `name` (e.g., `sms-flink-report-daily`)
- **Request Body (`TriggerScheduleRequest`)**:
```json
{
  "mode": "EVENTBRIDGE",
  "ref": "main",
  "inputs": {
    "target_env": "finance",
    "pipeline_stage": "report",
    "report_period": "DAILY"
  }
}
```
  - `mode` (`string`, required): `"EVENTBRIDGE"` (default one-click trigger) or `"GITHUB_DIRECT"` (custom parameter modal).
  - `ref` (`string`, optional): Git branch ref (defaults to schedule's `targetRef`, e.g., `"main"`).
  - `inputs` (`Map<String, String>`, optional): Overridden workflow inputs (used when `mode == "GITHUB_DIRECT"`).
- **Backend Execution Logic**:
  - **If `mode == "EVENTBRIDGE"`**:
    Calls `EventBridgeClient.putEvents` on `default` bus with:
    - `Source` = `schedule.routing.source`
    - `DetailType` = `schedule.routing.detailType`
    - `Detail` = `{"triggeredBy": "{username}", "triggerMode": "MANUAL_DASHBOARD", "scheduleName": "{name}"}`
    *(AWS EventBridge Rule matches the event, injects the rule's target inputs, and invokes the GitHub API Destination).* 
  - **If `mode == "GITHUB_DIRECT"`**:
    Calls GitHub REST API `POST /repos/{owner}/{repo}/actions/workflows/{workflowFile}/dispatches` directly with `{"ref": ref, "inputs": mergedInputs}`.
  - **Cache Eviction**: Invalidates the run history cache for `{owner}/{repo}/{workflowFile}`.
- **Response (`202 Accepted`)**:
```json
{
  "code": 0,
  "message": "Schedule triggered successfully via EVENTBRIDGE",
  "data": {
    "scheduleName": "sms-flink-report-daily",
    "mode": "EVENTBRIDGE",
    "eventId": "6f8a9c12-3d4e-5f6a-7b8c-9d0e1f2a3b4c",
    "dispatchedAt": "2026-10-18T08:31:10Z",
    "triggeredBy": "nvd11",
    "targetWorkflow": "nvd11/sms-flink-etl/trigger-nuc-batch-runner.yml",
    "recommendedPollDelayMs": 4000
  },
  "timestamp": "2026-10-18T08:31:10Z"
}
```

---

#### 3.6.2 `PATCH /v1/schedules/{name}/state` — Pause or Resume Schedule
Toggles an existing schedule between `ENABLED` and `DISABLED`.

- **Path Parameter**: `name`
- **Request Body (`UpdateScheduleStateRequest`)**:
```json
{
  "state": "DISABLED"
}
```
- **Backend Execution Logic (`JobControlService`)**:
  1. Validate `state` is either `"ENABLED"` or `"DISABLED"`.
  2. Call `SchedulerClient.getSchedule(name, groupName)` to retrieve the full existing specification (`ScheduleExpression`, `ScheduleExpressionTimezone`, `Description`, `Target`, `FlexibleTimeWindow`).
  3. Call `SchedulerClient.updateSchedule(...)` with the new `State` while preserving all other fields intact (AWS `UpdateSchedule` is a full replacement call, so reading existing fields first is mandatory).
  4. Evict the topology cache so subsequent `GET /v1/schedules` calls reflect the new state immediately.
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "Schedule state updated to DISABLED",
  "data": {
    "name": "sms-flink-report-daily",
    "previousState": "ENABLED",
    "currentState": "DISABLED",
    "scheduleArn": "arn:aws:scheduler:ap-southeast-1:186004631963:schedule/default/sms-flink-report-daily",
    "updatedAt": "2026-10-18T08:35:00Z"
  },
  "timestamp": "2026-10-18T08:35:00Z"
}
```

---

#### 3.6.3 `PUT /v1/schedules/{name}` — Update Cron Expression, Timezone, or Description
Modifies the schedule expression, timezone, or description of an existing schedule.

- **Path Parameter**: `name`
- **Request Body (`UpdateScheduleConfigRequest`)**:
```json
{
  "scheduleExpression": "cron(30 23 * * ? *)",
  "timezone": "Asia/Shanghai",
  "description": "Nightly 23:30 (Asia/Shanghai) financial daily report trigger",
  "state": "ENABLED"
}
```
- **Backend Execution Logic (`JobControlService`)**:
  1. Pre-validate `scheduleExpression` (`rate(...)` or 6-field AWS `cron(...)`) and `timezone` (`ZoneId.of(timezone)`) using `AwsCronEvaluator.validate(...)`. If invalid, return HTTP `400 Bad Request` before calling AWS.
  2. Fetch current schedule via `SchedulerClient.getSchedule` to preserve `Target` and `FlexibleTimeWindow`.
  3. Execute `SchedulerClient.updateSchedule`.
  4. Evict the topology cache and return the updated `SchedulePipelineView`.
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "Schedule configuration updated successfully",
  "data": {
    "name": "sms-flink-report-daily",
    "scheduleExpression": "cron(30 23 * * ? *)",
    "timezone": "Asia/Shanghai",
    "humanReadableCron": "Every day at 23:30 (Asia/Shanghai)",
    "nextFireTime": "2026-10-18T15:30:00Z",
    "state": "ENABLED"
  },
  "timestamp": "2026-10-18T08:36:00Z"
}
```

---

#### 3.6.4 `POST /v1/cron/preview` — Validate & Preview Cron Expression (Helper Endpoint)
Used by the frontend Cron Editor modal as the operator types a new `cron(...)` or `rate(...)` expression, providing instant validation, human-readable translation, and the next 5 fire times without mutating AWS.

- **Request Body (`CronPreviewRequest`)**:
```json
{
  "scheduleExpression": "cron(0 9 ? * MON *)",
  "timezone": "Asia/Shanghai"
}
```
- **Response (`200 OK`)**:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "valid": true,
    "humanReadableCron": "At 09:00 AM, only on Monday (Asia/Shanghai)",
    "upcomingFireTimes": [
      "2026-10-19T01:00:00Z",
      "2026-10-26T01:00:00Z",
      "2026-11-02T01:00:00Z",
      "2026-11-09T01:00:00Z",
      "2026-11-16T01:00:00Z"
    ],
    "errorMessage": null
  },
  "timestamp": "2026-10-18T08:37:00Z"
}
```

---

## 4. Error Code Catalogue (`GlobalExceptionMapper`)

A single JAX-RS `@Provider ExceptionMapper<Throwable>` maps all domain, validation, and upstream SDK exceptions into the standard `ApiResponse<Void>` envelope:

| HTTP Status | Error `code` | Scenario / Trigger |
| :---: | :---: | :--- |
| `400 Bad Request` | `40001` | Invalid `cron(...)` / `rate(...)` syntax or unknown IANA `timezone` |
| `400 Bad Request` | `40002` | Invalid `mode` or `state` enum value in request payload |
| `401 Unauthorized` | `40100` | Missing `X-Auth-Request-User` header when `APP_AUTH_ENABLED=true` |
| `404 Not Found` | `40401` | Schedule `{name}` does not exist in AWS EventBridge Scheduler |
| `404 Not Found` | `40402` | Schedule `{name}` has no linked GitHub Actions workflow target |
| `502 Bad Gateway` | `50201` | Upstream AWS SDK exception (`SchedulerException`, `EventBridgeException`, `CloudWatchException`) |
| `502 Bad Gateway` | `50202` | Upstream GitHub REST API error or rate-limit (`HTTP 403/422/5xx`) |
| `500 Internal Error` | `50000` | Unexpected internal runtime exception |

---

## 5. Backend Package & Class Blueprint (`asia.jppwl.scheduler`)

```text
backend/
├── pom.xml
├── Dockerfile.native
└── src/
    ├── main/
    │   ├── java/asia/jppwl/scheduler/
    │   │   ├── config/
    │   │   │   ├── AppConfig.java                  # @ConfigMapping(prefix = "app") for auth, AWS, GitHub, cache TTLs
    │   │   │   └── AwsClientProducer.java          # CDI producers if custom config needed alongside Quarkus AWS extensions
    │   │   ├── model/
    │   │   │   ├── ApiResponse.java                # @RegisterForReflection generic envelope (code, message, data, timestamp)
    │   │   │   ├── UserProfile.java                # @RegisterForReflection SSO user DTO
    │   │   │   ├── SchedulePipelineView.java       # @RegisterForReflection main schedule + routing + metrics + lastExecution DTO
    │   │   │   ├── ScheduleDetailView.java         # @RegisterForReflection detail DTO with upcomingFireTimes & retryPolicy
    │   │   │   ├── ExecutionRecord.java            # @RegisterForReflection GitHub workflow run DTO
    │   │   │   ├── RunLogDetailView.java           # @RegisterForReflection Job steps + plain-text console log DTO
    │   │   │   ├── TriggerScheduleRequest.java     # @RegisterForReflection POST /trigger payload
    │   │   │   ├── TriggerScheduleResult.java      # @RegisterForReflection POST /trigger response
    │   │   │   ├── UpdateScheduleStateRequest.java # @RegisterForReflection PATCH /state payload
    │   │   │   ├── UpdateScheduleConfigRequest.java# @RegisterForReflection PUT /{name} payload
    │   │   │   └── CronPreviewDto.java             # @RegisterForReflection POST /v1/cron/preview req/res
    │   │   ├── security/
    │   │   │   └── ForwardAuthIdentityService.java # Extracts X-Auth-Request-* headers + optional Logto /oidc/me lookup
    │   │   ├── client/
    │   │   │   ├── GitHubActionsClient.java        # @RegisterRestClient for api.github.com (/runs & /dispatches)
    │   │   │   └── LogtoOidcClient.java            # @RegisterRestClient for sodaxw.logto.app (/oidc/me)
    │   │   ├── service/
    │   │   │   ├── AwsCronEvaluator.java           # Pure Java 21 AWS 6-field cron & rate parser + nextFireTime calculator
    │   │   │   ├── AwsTopologyService.java         # Resolves & caches Scheduler -> Rule -> API Destination graph
    │   │   │   ├── CloudWatchService.java          # Batch queries 7-day AWS/Scheduler & AWS/Events metrics
    │   │   │   ├── ExecutionCorrelatorService.java # Dual-layer (display_title + cron window) GitHub run attribution
    │   │   │   └── JobControlService.java          # PutEvents trigger, GitHub direct dispatch, UpdateSchedule mutations
    │   │   └── resource/
    │   │       ├── GlobalExceptionMapper.java      # Maps exceptions to ApiResponse<Void>
    │   │       ├── HealthResource.java             # GET /live, GET /ready
    │   │       ├── AuthResource.java               # GET /auth/me, POST /auth/logout
    │   │       └── SchedulerResource.java          # GET/POST/PATCH/PUT /v1/schedules/* & /v1/cron/preview
    │   └── resources/
    │       └── application.properties              # Quarkus HTTP, Jackson, Caffeine, AWS URLConnection, Native flags
    └── test/
        └── java/asia/jppwl/scheduler/
            ├── service/
            │   ├── AwsCronEvaluatorTest.java       # Unit tests for all 5 production cron/rate expressions & timezones
            │   └── ExecutionCorrelatorTest.java    # Unit tests for Tier 1 (display_title) & Tier 2 (cron window) attribution
            └── resource/
                └── SchedulerResourceTest.java      # @QuarkusTest REST endpoint contract tests with mocked AWS/GitHub
```
