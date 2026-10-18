export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

export interface UserProfile {
  id: string;
  username: string;
  email: string | null;
  avatarUrl: string | null;
  authMode: string;
}

export interface DashboardSummary {
  totalSchedules: number;
  enabledSchedules: number;
  disabledSchedules: number;
  runningOrQueuedJobs: number;
  invocations7d: number;
  successRate7d: number;
  awsAccountId: string;
  awsRegion: string;
}

export interface ScheduleRoutingView {
  eventBusName: string;
  source: string | null;
  detailType: string | null;
  matchedRuleName: string | null;
  matchedRuleArn: string | null;
  apiDestinationName: string | null;
  invocationEndpoint: string | null;
  githubOwner: string | null;
  githubRepo: string | null;
  workflowFile: string | null;
  targetRef: string;
  defaultInputs: Record<string, string>;
}

export interface DailyMetricBucket {
  date: string;
  invocations: number;
  failures: number;
}

export interface Metrics7dView {
  invocations: number;
  successfulInvocations: number;
  failedInvocations: number;
  dailyBuckets: DailyMetricBucket[];
}

export interface ExecutionRecord {
  runId: number;
  runNumber: number;
  displayTitle: string;
  attributionMethod: 'DISPLAY_TITLE' | 'CRON_WINDOW' | 'SINGLE_OWNER' | string;
  event: string;
  status: 'queued' | 'in_progress' | 'completed' | string;
  conclusion: 'success' | 'failure' | 'cancelled' | 'skipped' | null;
  startedAt: string | null;
  updatedAt: string | null;
  durationSeconds: number;
  htmlUrl: string;
}

export interface SchedulePipelineView {
  name: string;
  groupName: string;
  arn: string;
  description: string;
  category: 'FINANCE_ETL' | 'FINANCE_REPORT' | 'INFRA_TLS' | string;
  state: 'ENABLED' | 'DISABLED' | string;
  scheduleExpression: string;
  timezone: string;
  humanReadableCron: string;
  nextFireTime: string | null;
  nextFireInSeconds: number;
  lastModifiedDate: string;
  routing: ScheduleRoutingView;
  metrics7d: Metrics7dView;
  lastExecution: ExecutionRecord | null;
}

export interface ScheduleListResponse {
  summary: DashboardSummary;
  items: SchedulePipelineView[];
}

export interface ScheduleRunsResponse {
  scheduleName: string;
  githubRepo: string | null;
  workflowFile: string | null;
  totalMatched: number;
  runs: ExecutionRecord[];
}

export interface JobSummaryItem {
  jobId: number;
  name: string;
  status: string;
  conclusion: string | null;
  durationSeconds: number;
}

export interface StepSummaryItem {
  number: number;
  name: string;
  status: string;
  conclusion: string | null;
  durationSeconds: number;
}

export interface RunLogDetailView {
  scheduleName: string;
  runId: number;
  jobId: number;
  jobName: string;
  status: string;
  conclusion: string | null;
  startedAt: string | null;
  completedAt: string | null;
  durationSeconds: number;
  htmlUrl: string;
  jobs: JobSummaryItem[];
  steps: StepSummaryItem[];
  truncated: boolean;
  totalLines: number;
  logContent: string;
}

export interface TriggerScheduleRequest {
  mode: 'EVENTBRIDGE' | 'GITHUB_DIRECT';
  ref?: string;
  inputs?: Record<string, string>;
}

export interface TriggerScheduleResult {
  scheduleName: string;
  mode: string;
  eventId: string;
  dispatchedAt: string;
  triggeredBy: string;
  targetWorkflow: string;
  recommendedPollDelayMs: number;
}

export interface UpdateScheduleConfigRequest {
  scheduleExpression: string;
  timezone: string;
  description: string;
  state?: string;
}

export interface UpdateScheduleConfigResult {
  name: string;
  scheduleExpression: string;
  timezone: string;
  humanReadableCron: string;
  nextFireTime: string | null;
  state: string;
}

export interface CronPreviewResponse {
  valid: boolean;
  humanReadableCron: string | null;
  upcomingFireTimes: string[];
  errorMessage: string | null;
}
