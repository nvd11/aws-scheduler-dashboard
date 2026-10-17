# Frontend Development Specification (`scheduler-frontend`)

**Document Version:** 1.0.0  
**Service Name:** `scheduler-frontend` (Directory: `web/`)  
**Technology Stack:** React 18 + TypeScript 5 + Vite 5 + Tailwind CSS 3 + Lucide React + Axios  
**Serving Runtime:** `nginx:1.27-alpine` (`<15MB RAM` footprint)  
**External URL:** `https://gw.jppwl.asia/scheduler/` (Cloudflare Same-Origin + Kong `oauth2-forward-auth` SSO)  
**Backend API Base URL:** `/scheduler/api` (Same-Origin relative path)  

---

## 1. Architectural Alignment with `astra-frontend`

### 1.1 Cloudflare Same-Origin & Vite Base Path
- **Vite Base Path (`vite.config.ts`)**: Configured with `base: '/scheduler/'` so all static assets (`index.html`, JS/CSS chunks) resolve cleanly under `/scheduler/assets/*`.
- **Local Development Proxy**: Vite's dev server (`port: 5173`) proxies `/scheduler/api` to `http://localhost:8080` with prefix rewriting (`path.replace(/^\/scheduler\/api/, '')`), mirroring Kong Gateway's production `strip-path: "true"` behavior 1:1:

```ts
export default defineConfig({
  base: '/scheduler/',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      '/scheduler/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/scheduler\/api/, ''),
      },
    },
  },
});
```

### 1.2 Zero-Token SSO Authentication Flow (Kong + Logto + GitHub)
- **No JWT in `localStorage`**: The browser relies strictly on the `HttpOnly; Secure; Domain=.jppwl.asia` `_oauth2_proxy` cookie managed by Kong Gateway and OAuth2-Proxy.
- **Axios Instance (`src/services/api.ts`)**:
  - Configured with `baseURL: import.meta.env.VITE_API_BASE_URL || '/scheduler/api'` and `withCredentials: true`.
  - **401 Interceptor**: When an API call returns `401 Unauthorized` (session expired), the interceptor redirects the browser to Kong's OAuth2 login initializer:
    ```ts
    const returnPath = window.location.pathname.startsWith('/scheduler')
      ? window.location.pathname
      : '/scheduler/';
    window.location.href = `/oauth2/start?rd=${encodeURIComponent(returnPath)}`;
    ```
- **User Identity Hydration**: On initial mount, `App.tsx` invokes `GET /scheduler/api/auth/me` to fetch the operator's GitHub profile (`username`, `email`, `avatarUrl`, `authMode`) and renders it in the top navigation bar.
- **One-Click SSO Logout**: Clicking the logout button navigates to `/oauth2/sign_out?rd=/scheduler/`, clearing the `.jppwl.asia` root cookie.

---

## 2. Visual Design & Layout Specification

### 2.1 Theme & Aesthetic Direction
- **Cloud-Native Dark Slate / Glassmorphism UI**:
  - Background: Deep slate gradient (`bg-slate-950` / `bg-slate-900`) with subtle translucent glass cards (`bg-slate-900/80 backdrop-blur-md border border-slate-800`).
  - Semantic Status Colors:
    - `ENABLED` / `success`: Emerald (`text-emerald-400`, `bg-emerald-500/10`, `border-emerald-500/30`)
    - `in_progress` / `queued`: Sky/Amber with pulse/spin animation (`text-sky-400`, `animate-spin`)
    - `DISABLED` / `cancelled`: Slate (`text-slate-400`, `bg-slate-800`)
    - `failure` / Error: Rose (`text-rose-400`, `bg-rose-500/10`, `border-rose-500/30`)
- **Zero Heavy Chart Dependencies**: All 7-day invocation sparklines and duration bars are rendered using lightweight native SVG components (`MetricSparkline.tsx`), keeping the JS bundle under `180KB` gzipped.

### 2.2 Three-Tier Single-Screen Layout

```text
+--------------------------------------------------------------------------------------------------+
| [Logo] AWS EventBridge Scheduler Control Plane   [ap-southeast-1 | 186004631963]  [Auto 30s] [⟳] |
|                                                                    [Avatar: nvd11] [Sign Out ↪]  |
+--------------------------------------------------------------------------------------------------+
|  +-----------------------+  +-----------------------+  +-------------------+  +---------------+  |
|  | Managed Schedules     |  | 7-Day Success Rate    |  | Active Workflow   |  | Next Firing   |  |
|  | 5 Total (5 Enabled)   |  | 100.0% (1,029 runs)   |  | 0 In-Progress     |  | in 03m 42s ⏱️ |  |
|  +-----------------------+  +-----------------------+  +-------------------+  +---------------+  |
+--------------------------------------------------------------------------------------------------+
|  Tabs: [All (5)] [Finance Flink ETL (4)] [Infra & TLS (1)]   State: [ALL▾]   [🔍 Search jobs...] |
+--------------------------------------------------------------------------------------------------+
|  +---------------------------------------------+  +-------------------------------------------+  |
|  | [● ENABLED] sms-flink-report-daily   [Toggle]|  | [● ENABLED] test-sms-flink-cron  [Toggle] |  |
|  | cron(50 23 * * ? *) · Asia/Shanghai      [✏️]|  | rate(10 minutes) · UTC                [✏️]|  |
|  | "Every day at 23:50" · Next in 07h 19m 12s  |  | "Every 10 minutes" · Next in 03m 42s      |  |
|  | ------------------------------------------- |  | ----------------------------------------- |  |
|  | Topo: finance.sms-flink-report / daily-rep  |  | Topo: finance.sms-flink / minutely-tick   |  |
|  |   ➔ nvd11/sms-flink-etl (report_period=DAILY)|  |   ➔ nvd11/sms-flink-etl (ODS+DWD Ingest) |  |
|  | ------------------------------------------- |  | ----------------------------------------- |  |
|  | Last Run: [✓ #88 Success] 2m 15s · 8h ago   |  | Last Run: [✓ #412 Success] 48s · 6m ago   |  |
|  | 7d Telemetry: [▁▂▁▁▁▁▁] 7/7 OK              |  | 7d Telemetry: [███████] 1,008/1,008 OK    |  |
|  | [⚡ Run Now ▾]       [📜 History & Logs (14)]|  | [⚡ Run Now ▾]    [📜 History & Logs (50)] |  |
|  +---------------------------------------------+  +-------------------------------------------+  |
+--------------------------------------------------------------------------------------------------+
```

---

## 3. Core Component Specifications

### 3.1 `TopNavbar.tsx` & `KpiSummaryBanner.tsx`
- **`TopNavbar`**:
  - Displays AWS Account ID (`186004631963`), Region (`ap-southeast-1`), and Scheduler Group (`default`).
  - **Auto-Refresh Toggle**: Cycles between `Off`, `15s`, and `30s` background polling.
  - **Force Refresh Button**: Calls `GET /v1/schedules?refresh=true` with a spinning icon indicator to bypass backend Caffeine caches.
  - **User Profile Pill**: Displays GitHub avatar (`user.avatarUrl`), `user.username` (`nvd11`), and the SSO Logout button.
- **`KpiSummaryBanner`**:
  - Card 1: **Total Schedules** (`summary.totalSchedules`, breakdown of `enabledSchedules` vs. `disabledSchedules`).
  - Card 2: **7-Day Invocation Reliability** (`summary.successRate7d%` and total `summary.invocations7d` from CloudWatch).
  - Card 3: **Active / Queued Runs** (`summary.runningOrQueuedJobs`; pulses blue when `> 0`).
  - Card 4: **Nearest Upcoming Fire Countdown**: Dynamically computes a 1-second live countdown to the schedule with the minimum `nextFireTime`.

### 3.2 `ScheduleCard.tsx` (Primary Governance Unit)
Each schedule card presents the 3-hop topology, real-time countdown, telemetry, and action controls:
1. **Header & State Switch**:
   - Displays schedule `name`, `category` badge (`FINANCE_ETL`, `FINANCE_REPORT`, `INFRA_TLS`), and description.
   - **Enable / Pause Switch**: Invokes `PATCH /v1/schedules/{name}/state` with `{ state: 'ENABLED' | 'DISABLED' }`. Uses optimistic UI state with rollback on failure.
2. **Cron & Live Countdown Bar**:
   - Displays raw `scheduleExpression`, `timezone` badge, and `humanReadableCron`.
   - Renders a live-ticking countdown (`Next fire in Xh Ym Zs`) computed client-side against `nextFireTime`.
   - Clicking the edit (`✏️`) icon opens `CronEditModal.tsx`.
3. **3-Hop Topology Visualizer (`MiniTopologyBar.tsx`)**:
   - Hop 1 (EventBridge): `routing.source` + `routing.detailType`
   - Hop 2 (Rule & API Dest): `routing.matchedRuleName` ➔ `routing.apiDestinationName`
   - Hop 3 (GitHub Target): `{githubOwner}/{githubRepo}` ➔ `{workflowFile}` (`@{targetRef}`) + key-value pills for `routing.defaultInputs` (e.g., `report_period=DAILY`).
4. **Last Execution & 7-Day Sparkline**:
   - Displays `lastExecution.status` / `conclusion` badge, `#runNumber`, `displayTitle`, `durationSeconds` formatted as `Xm Ys`, and relative time (`X ago`).
   - Clicking the status badge or the **`📜 History & Logs`** button opens `RunHistoryAndLogDrawer.tsx` pre-selected on that schedule.
   - Renders `MetricSparkline.tsx` using `metrics7d.dailyBuckets` (emerald bars for successful invocations, rose bars if `failures > 0`).
5. **Action Footer**:
   - **Primary Button (`⚡ Run Now`)**: Fires a 1-click default trigger (`mode: 'EVENTBRIDGE'`) or opens `TriggerModal.tsx` when clicking **`⚙️ Run with Params`**.
   - **Secondary Button (`📜 History & Logs`)**: Opens the slide-over execution history and terminal log drawer.

### 3.3 `RunHistoryAndLogDrawer.tsx` & `TerminalLogViewer.tsx` (In-App Observability)
A wide slide-over drawer (`max-w-5xl`) divided into two interactive panes:
- **Left Pane — Attributed Run History List (`GET /v1/schedules/{name}/runs`)**:
  - Lists the last 20 attributed GitHub Actions runs.
  - Each row shows: Status icon (`✓` / `✗` / spinning loader), `#runNumber`, `displayTitle`, **Attribution Badge** (`Exact Title` for `DISPLAY_TITLE` vs. `Cron Window` for `CRON_WINDOW`), start timestamp (`Asia/Shanghai` & `UTC`), duration, and an external GitHub link icon.
  - Selecting any run row highlights it and loads its steps & console logs in the right pane.
- **Right Pane — Step Breakdown & Built-in Terminal Log Viewer (`GET /v1/schedules/{name}/runs/{runId}/logs`)**:
  - **Step Progress Strip**: Horizontal or collapsible list of `steps[]` (`1. Set up job (2s) ✅`, `2. Trigger Flink Report (131s) ✅`), highlighting any failed step in rose red.
  - **Dark Monospace Console (`TerminalLogViewer.tsx`)**:
    - Renders `logContent` with line numbers and syntax coloring (`ERROR`/`##[error]` in red, `WARN` in amber, `SUCCESS` in emerald, timestamps in muted slate).
    - Toolbar controls:
      - **Search input**: Highlights and filters matching log lines client-side.
      - **Tail Lines selector**: Toggle between `500 lines` and `2000 lines`.
      - **Copy Logs button**: Copies raw log output to clipboard.
      - **Jump to Bottom button**: Scrolls immediately to the latest log line.
      - **Live Polling**: If the selected run is `queued` or `in_progress`, automatically refreshes steps and logs every 5 seconds.

### 3.4 `TriggerModal.tsx` (Dual-Mode Manual Dispatch)
Allows the operator to choose between two execution modes before dispatching `POST /v1/schedules/{name}/trigger`:
1. **Mode 1: Native AWS EventBridge Trigger (`EVENTBRIDGE` - Default)**:
   - Shows a read-only preview of the EventBridge event (`EventBus: default`, `Source`, `DetailType`).
   - Validates the complete cloud pipeline (`AWS EventBridge ➔ Rule ➔ API Destination ➔ GitHub Actions`).
2. **Mode 2: Direct GitHub Workflow Dispatch with Custom Parameters (`GITHUB_DIRECT`)**:
   - Pre-populates editable input fields from `routing.defaultInputs` (e.g., `target_env`, `pipeline_stage`, `report_period`, or `threshold_days`) plus an option to add custom key-value inputs or change the target branch `ref` (`main`).
3. **Post-Trigger Live Feedback Loop**:
   - Upon `202 Accepted`, displays a toast notification (`"Triggered! Polling GitHub Actions run status..."`).
   - Automatically schedules a `setTimeout(..., 4000)` to call `GET /v1/schedules/{name}/runs?refresh=true` and `GET /v1/schedules?refresh=true`, transitioning the card badge to `Running / Queued` and polling every 5 seconds until completion.

### 3.5 `CronEditModal.tsx` (Live Cron Validation & Next-5 Preview)
- Allows editing `scheduleExpression` (`cron(...)` or `rate(...)`), `timezone` (`Asia/Shanghai`, `UTC`, etc.), and `description`.
- **Quick Preset Chips**: One-click presets (`Every 10 mins`, `Daily 23:50 CST`, `Weekly Mon 09:00 CST`, `Monthly 1st 09:30 CST`).
- **Debounced Live Preview (`POST /v1/cron/preview`)**:
  - 300ms after the operator stops typing, calls `/v1/cron/preview`.
  - If `valid === true`: Renders the green `humanReadableCron` banner and lists the **next 5 exact firing timestamps** (shown in both the schedule's timezone and local browser time).
  - If `valid === false`: Displays `errorMessage` in red and disables the **Save Changes** button (`PUT /v1/schedules/{name}`).

---

## 4. Frontend Directory & File Blueprint (`web/`)

```text
web/
├── Dockerfile                          # Multi-stage Node 22 build -> nginx:1.27-alpine (/usr/share/nginx/html/scheduler)
├── nginx.conf                          # SPA routing for /scheduler/, gzip, and /health endpoint
├── index.html
├── package.json
├── tsconfig.json
├── vite.config.ts                      # base: '/scheduler/', proxy '/scheduler/api' -> localhost:8080
├── tailwind.config.js
├── postcss.config.js
├── .env.development                    # VITE_API_BASE_URL=/scheduler/api
├── .env.production                     # VITE_API_BASE_URL=/scheduler/api
└── src/
    ├── main.tsx
    ├── App.tsx                         # Main layout, state orchestration & auto-polling loop
    ├── index.css                       # Tailwind directives & custom scrollbar/terminal styles
    ├── types/
    │   └── scheduler.ts                # TypeScript interfaces matching BACKEND_API_SPEC.md DTOs
    ├── services/
    │   ├── api.ts                      # Axios instance (baseURL: '/scheduler/api', 401 SSO redirect)
    │   ├── authService.ts              # getCurrentUser (/auth/me), gatewayLogout, gatewayLogin
    │   └── schedulerService.ts         # Typed API methods for schedules, runs, logs, trigger, state, cron
    ├── utils/
    │   └── format.ts                   # Duration formatter (135s -> '2m 15s'), relative time, countdown helpers
    └── components/
        ├── TopNavbar.tsx               # Header with region badge, refresh controls & GitHub user pill
        ├── KpiSummaryBanner.tsx        # 4 top-level metric cards with live next-fire countdown
        ├── ScheduleCard.tsx            # Schedule card with toggle, 3-hop topology, sparkline & actions
        ├── MetricSparkline.tsx         # Lightweight SVG 7-day invocation/failure bar chart
        ├── RunHistoryAndLogDrawer.tsx  # Slide-over drawer with attributed runs list + step/log viewer
        ├── TerminalLogViewer.tsx       # Built-in dark terminal with search, line numbers & colorizing
        ├── TriggerModal.tsx            # EventBridge vs. GitHub Direct parameter override modal
        └── CronEditModal.tsx           # Cron/Rate & Timezone editor with live /v1/cron/preview validation
```

---

## 5. Production Container & Nginx Routing (`web/Dockerfile` & `web/nginx.conf`)

Aligned 1:1 with `astra-frontend`:
- **Build Stage**: Uses `FROM --platform=$BUILDPLATFORM node:22-alpine AS builder` to execute native architecture compilation (`npm ci && npm run build`).
- **Runtime Stage**: Uses `nginx:1.27-alpine`, copying `dist/` into `/usr/share/nginx/html/scheduler` and symlinking `/usr/share/nginx/html/index.html`.
- **Nginx Locations**:
  - `location = /health`: Returns `200 'OK'` for K3s liveness/readiness probes.
  - `location = /scheduler`: `301` redirect to `/scheduler/`.
  - `location /scheduler/`: `try_files $uri $uri/ /scheduler/index.html;` for SPA client-side routing.
