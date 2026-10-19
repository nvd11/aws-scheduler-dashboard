# AWS EventBridge Scheduler Dashboard ⏱️🚀

> **Unified Control Plane & Execution Observability Dashboard for AWS EventBridge Scheduler & GitHub Actions Pipelines**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Backend](https://img.shields.io/badge/Backend-Java%2021%20%7C%20Quarkus%203.8%20Native-4695EB.svg)](#)
[![Frontend](https://img.shields.io/badge/Frontend-React%2018%20%7C%20Vite%20%7C%20Tailwind-61DAFB.svg)](#)
[![Gateway](https://img.shields.io/badge/Auth-Cloudflare%20%7C%20Kong%20%7C%20Logto%20SSO-009688.svg)](#)
[![Cloud](https://img.shields.io/badge/AWS-EventBridge%20Scheduler-FF9900.svg)](#)
[![Status](https://img.shields.io/badge/Status-Architecture%20%26%20Specification-orange.svg)](#)

---

## 📖 1. Project Background & Motivation

**AWS EventBridge Scheduler** provides a serverless, highly reliable, and cost-effective cron scheduling engine (14,000,000 free invocations/month under the AWS Always Free tier). However, using AWS EventBridge Console directly for daily operations and pipeline management suffers from three major operational pain points:

1. **Zero Native Execution History Log**: Unlike traditional job schedulers (e.g., Airflow, XXL-Job, or Rundeck), AWS EventBridge Scheduler is a stateless fire-and-forget trigger. It does **not** provide a built-in table showing past execution timestamps, run statuses (`SUCCESS` / `FAILURE`), execution duration, or downstream job logs.
2. **No "Run Now" Manual Trigger Button**: Testing or manually re-running a scheduled job in AWS Console requires either temporarily modifying the cron expression to a few minutes in the future or manually crafting a JSON payload in the EventBridge EventBus "Send Events" tool.
3. **Fragmented Multi-Hop Topology**: In modern GitOps & Cloud-Native architectures, a schedule often routes through an EventBridge `Rule` ➔ `API Destination` ➔ `GitHub Actions Workflow` (e.g., triggering Flink lakehouse batch ETL jobs or automated TLS certificate renewals). Operators must jump across 4 different AWS and GitHub pages to trace a single job's config and outcome.

**AWS EventBridge Scheduler Dashboard (`aws-scheduler-dashboard`)** bridges this gap without reinventing the scheduling wheel. Instead of building a heavy, stateful scheduler, it acts as a **stateless, ultra-lightweight Java 21 (Quarkus GraalVM/Mandrel Native) + React 18 Control Plane** (`<64MB RAM` footprint) that correlates AWS EventBridge schedules with CloudWatch telemetry and downstream GitHub Actions workflow runs in a single unified glass pane.

It aligns 1:1 with the **`astra` service deployment & authentication architecture**:
- **Cloudflare Same-Origin Routing**: Frontend (`/scheduler/`) and Backend API (`/scheduler/api/*`) are served under the unified Cloudflare domain (`https://gw.jppwl.asia` / `https://gw-tx.jppwl.asia`), completely eliminating browser CORS preflight overhead.
- **Zero-Trust Gateway SSO**: Protected by **Kong Gateway (`oauth2-forward-auth`) + OAuth2-Proxy + Logto (GitHub OAuth)**, sharing the root `.jppwl.asia` session cookie with Astra, LiteLLM, and DbGate.
- **Dual-Pod GitOps Deployment**: Deployed as two independent ArgoCD applications (`scheduler-frontend` on Nginx Alpine and `scheduler-backend` compiled to a GraalVM Native binary) via `generic-web-service-v2`.

---

## 🎯 2. Core Capabilities & Value Proposition

```mermaid
flowchart LR
    subgraph Edge["Cloudflare + Kong Same-Origin Edge"]
        CF["Cloudflare Edge SSL<br/>gw.jppwl.asia / gw-tx.jppwl.asia"]
        Kong["Kong Gateway + OAuth2-Proxy<br/>Logto + GitHub SSO (oauth2-forward-auth)"]
        CF --> Kong
    end

    subgraph UI["scheduler-frontend (React 18 + Nginx)"]
        Cards["Schedule Overview Cards<br/>• Cron / Rate & Next Fire Time<br/>• Enable / Pause Toggle<br/>• One-Click 'Trigger Now'"]
        History["Unified Execution History<br/>• GitHub Actions Run Status<br/>• Duration & Trigger Actor<br/>• Direct Log Deep-Links"]
        Metrics["CloudWatch Telemetry<br/>• 7-Day Invocation Chart<br/>• Failed / Throttled Alerts"]
    end

    subgraph Backend["scheduler-backend (Quarkus 3.8 Native <64MB)"]
        Auth["Forward-Auth Identity<br/>(X-Auth-Request-* / Logto)"]
        Topo["Topology Resolver<br/>(Schedule ➔ Rule ➔ Target)"]
        Trigger["Manual Dispatch Engine<br/>(EventBus PutEvents / GH Dispatch)"]
        Correlator["Execution History Correlator<br/>(CloudWatch + GitHub Runs API)"]
    end

    subgraph Cloud["External Cloud Providers"]
        AWS_Sched["AWS EventBridge Scheduler<br/>(ap-southeast-1)"]
        AWS_Bus["AWS EventBridge Rules &<br/>API Destinations"]
        AWS_CW["AWS CloudWatch Metrics<br/>(AWS/Scheduler & AWS/Events)"]
        GH_API["GitHub Actions REST API<br/>(Workflow Runs & Dispatches)"]
    end

    Kong -->|/scheduler/| UI
    Kong -->|/scheduler/api/* (strip-path)| Backend
    Topo --> AWS_Sched & AWS_Bus
    Trigger --> AWS_Bus & GH_API
    Correlator --> AWS_CW & GH_API
```

### 1. Automatic End-to-End Topology Discovery
- Dynamically queries AWS EventBridge Scheduler (`ListSchedules` / `GetSchedule`), EventBridge Rules (`ListRules` / `ListTargetsByRule`), and API Destinations (`ListApiDestinations`).
- Automatically resolves the complete routing chain for every job:
  - **Schedule Layer**: Name, Group, Cron/Rate Expression, Timezone (e.g., `Asia/Shanghai`, `UTC`), Next Fire Time, and State (`ENABLED` / `DISABLED`).
  - **Event Routing Layer**: Source & Detail-Type matching rules (e.g., `finance.sms-flink-report` / `daily-report`).
  - **Target Execution Layer**: Target GitHub Repository, Workflow File (`.yml`), Branch (`ref`), and injected JSON `inputs` (e.g., `target_env=finance`, `report_period=DAILY`).

### 2. Unified Execution History & Dual-Layer Run Attribution
- **Clear API Responsibility Split**:
  - **AWS APIs (`scheduler:*`, `events:*`, `cloudwatch:*`)** provide the schedule inventory, 3-hop routing rules, manual `PutEvents` triggers, and 7-day aggregate invocation telemetry (`InvocationAttemptCount`, `FailedInvocations`).
  - **GitHub Actions REST API (`/actions/workflows/{workflow_id}/runs` & `/actions/jobs/{job_id}/logs`)** provides the actual execution history, step-by-step status breakdown, and plain-text console log streams for the built-in Terminal Log Viewer (since AWS EventBridge Scheduler is a stateless fire-and-forget trigger).
- **Dual-Layer Multi-Schedule Disambiguation**:
  - When multiple schedules (`sms-flink-ingest-10m`, `daily`, `weekly`, `monthly`) share a single workflow file (`trigger-nuc-batch-runner.yml`), the backend correlates runs using:
    1. **Layer 1 (Exact `run-name` / `display_title` Match)**: Matches workflow `display_title` tags (e.g., `Batch ETL [report - DAILY]`) against each schedule's injected `inputs`—100% accurate even for manual `Run Now` triggers.
    2. **Layer 2 (Cron Firing Window Fallback)**: Aligns legacy run `created_at` timestamps with each schedule's computed cron firing window (`±180s`).

### 3. One-Click Manual Trigger ("Run Now") & Parameter Override
- Provides an instant **"Trigger Now" (`立即运行`)** button on every schedule card.
- Supports two execution modes:
  1. **Native EventBridge Emulation**: Calls AWS `events:PutEvents` with the exact `Source`, `DetailType`, and `Input` payload configured on the schedule, exercising the full AWS Rule ➔ API Destination pipeline.
  2. **Direct GitHub Workflow Dispatch (with Custom Inputs)**: Allows operators to preview and optionally override workflow `inputs` (e.g., switching `force_renew` from `false` to `true`, or selecting `report_period=WEEKLY`) before dispatching directly to GitHub Actions.

### 4. Lifecycle & Cron Management (Existing Schedules Only)
- **Focused Governance Scope**: Designed to govern existing schedules provisioned via IaC/CLI (no schedule creation/deletion UI).
- **Instant Pause / Resume**: One-click toggle to switch an existing schedule between `ENABLED` and `DISABLED` via `scheduler:UpdateSchedule`.
- **Cron & Timezone Editor**: Interactive modal with human-readable cron translation (e.g., `cron(50 23 * * ? *)` ➔ *"Every day at 23:50 Asia/Shanghai"*) and next-5-fire-times preview.

---

## 📋 3. Target Production Schedules Inventory (`ap-southeast-1`)

The dashboard is designed out-of-the-box to govern and monitor the following active schedules in AWS Account `186004631963`:

| Schedule Name | Schedule Expression | Timezone | Event Source / Detail-Type | Downstream Target Workflow & Inputs |
| :--- | :--- | :---: | :--- | :--- |
| **`sms-flink-ingest-10m`** | `rate(10 minutes)` | `UTC` | `finance.sms-flink`<br>`minutely-tick` | `nvd11/sms-flink-etl`<br>`trigger-nuc-batch-runner.yml` *(ODS+DWD Incremental Ingest)* |
| **`sms-flink-report-daily`** | `cron(50 23 * * ? *)` | `Asia/Shanghai` | `finance.sms-flink-report`<br>`daily-report` | `nvd11/sms-flink-etl`<br>`trigger-nuc-batch-runner.yml` (`report_period=DAILY`) |
| **`sms-flink-report-weekly`** | `cron(0 9 ? * MON *)` | `Asia/Shanghai` | `finance.sms-flink-report`<br>`weekly-report` | `nvd11/sms-flink-etl`<br>`trigger-nuc-batch-runner.yml` (`report_period=WEEKLY`) |
| **`sms-flink-report-monthly`** | `cron(30 9 1 * ? *)` | `Asia/Shanghai` | `finance.sms-flink-report`<br>`monthly-report` | `nvd11/sms-flink-etl`<br>`trigger-nuc-batch-runner.yml` (`report_period=MONTHLY`) |
| **`jppwl-tls-cert-renew-weekly`** | `cron(0 4 ? * SUN *)` | `Asia/Shanghai` | `infra.tls-cert`<br>`renew-jppwl-tls` | `nvd11/my-argocd-manifests`<br>`renew-jppwl-tls.yml` (`threshold_days=30`) |

---

## 📂 4. Planned Repository Structure

```text
aws-scheduler-dashboard/
├── README.md                           # Project overview, capabilities & quickstart
├── LICENSE                             # MIT License
├── .env-template                       # Standardized environment variable template
├── .gitignore                          # Git ignore rules for Java/Maven & Node/Vite
├── docs/
│   ├── REQUIREMENTS.md                 # System Requirements Specification (SRS)
│   ├── ARCHITECTURE.md                 # Full-Stack System Architecture, APIs & Sequence Flows
│   ├── BACKEND_API_SPEC.md             # Detailed Backend REST API & DTO Development Specification
│   ├── FRONTEND_DEV_SPEC.md            # Detailed Frontend SPA, Components & SSO Development Specification
│   └── DEPLOYMENT_SPEC.md              # GitOps, OCI Vault ExternalSecret, Native Docker & CI/CD Specification
├── backend/                            # Java 21 + Quarkus 3.8 GraalVM Native REST Backend (<64MB RAM)
│   ├── pom.xml                         # Maven dependencies (Quarkus REST Jackson, AWS SDK v2 URLConnection, Native profile)
│   ├── Dockerfile.native               # Multi-stage Mandrel/GraalVM Native image build (quarkus-micro-image)
│   └── src/main/java/asia/jppwl/scheduler/
│       ├── config/                     # AWS & GitHub client producers and config mapping
│       ├── model/                      # @RegisterForReflection DTOs (SchedulePipelineView, UserProfile, etc.)
│       ├── security/                   # ForwardAuthIdentityService (X-Auth-Request-* + Logto /oidc/me enrichment)
│       ├── service/
│       │   ├── AwsCronEvaluator.java   # Zero-reflection AWS 6-field Cron & Rate parser
│       │   ├── AwsTopologyService.java # Correlates Scheduler -> EventBridge Rule -> API Destination
│       │   ├── CloudWatchService.java  # Fetches Invocation/Failure metrics per schedule/rule
│       │   ├── GitHubActionsClient.java# Queries workflow_runs & triggers workflow_dispatch
│       │   └── JobControlService.java  # Handles Pause/Resume, Cron Update & PutEvents Trigger
│       └── resource/
│           ├── AuthResource.java       # GET /auth/me & POST /auth/logout (Kong Forward-Auth integration)
│           ├── HealthResource.java     # GET /live, /ready, /v1/health
│           └── SchedulerResource.java  # REST API controllers (/v1/schedules, /trigger, /runs)
└── web/                                # React 18 + TypeScript + Vite + Tailwind CSS Frontend (scheduler-frontend)
    ├── Dockerfile                      # Multi-stage Node 22 build -> nginx:1.27-alpine (/usr/share/nginx/html/scheduler)
    ├── nginx.conf                      # SPA routing for /scheduler/ and /health probe
    ├── package.json
    ├── vite.config.ts                  # base: '/scheduler/', dev proxy '/scheduler/api' -> localhost:8080
    └── src/
        ├── components/                 # ScheduleCard, RunHistoryDrawer, TriggerModal, MetricSparkline, HeaderUserBadge
        ├── pages/                      # DashboardHome, PipelineTopologyView
        ├── services/                   # Typed Axios API client (baseURL: '/scheduler/api', withCredentials: true)
        └── App.tsx
```

---

## 🏗️ 5. Documentation Index

Detailed functional requirements, REST API contracts, data models, and deployment topologies are documented in:
- 📄 **[System Requirements Specification (SRS)](docs/REQUIREMENTS.md)**
- 🏛️ **[System Architecture & Technical Specification](docs/ARCHITECTURE.md)**
- 🔌 **[Backend REST API Development Specification](docs/BACKEND_API_SPEC.md)**
- 🎨 **[Frontend Development Specification](docs/FRONTEND_DEV_SPEC.md)**
- 🚀 **[Deployment, GitOps & CI/CD Specification](docs/DEPLOYMENT_SPEC.md)**
