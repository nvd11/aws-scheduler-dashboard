import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '@/App';
import { authService } from '@/services/authService';
import { schedulerService } from '@/services/schedulerService';
import { SchedulePipelineView } from '@/types/scheduler';

jest.mock('@/services/authService');
jest.mock('@/services/schedulerService');

const mockAuthService = authService as jest.Mocked<typeof authService>;
const mockSchedulerService = schedulerService as jest.Mocked<typeof schedulerService>;

const sampleSchedule: SchedulePipelineView = {
  name: 'sms-flink-report-daily',
  groupName: 'default',
  arn: 'arn:aws:scheduler:ap-southeast-1:186004631963:schedule/default/sms-flink-report-daily',
  description: 'Nightly 23:50 financial daily report trigger',
  category: 'FINANCE_REPORT',
  state: 'ENABLED',
  scheduleExpression: 'cron(50 23 * * ? *)',
  timezone: 'Asia/Shanghai',
  humanReadableCron: 'Every day at 23:50 (Asia/Shanghai)',
  nextFireTime: '2026-10-18T15:50:00Z',
  nextFireInSeconds: 3600,
  lastModifiedDate: '2026-10-14T18:30:06Z',
  routing: {
    eventBusName: 'default',
    source: 'finance.sms-flink-report',
    detailType: 'daily-report',
    matchedRuleName: 'rule-trigger-github-daily-report',
    matchedRuleArn: 'arn:aws:events:ap-southeast-1:186004631963:rule/rule-trigger-github-daily-report',
    apiDestinationName: 'github-trigger-nuc-batch',
    invocationEndpoint: 'https://api.github.com/repos/nvd11/sms-flink-etl/actions/workflows/trigger-nuc-batch-runner.yml/dispatches',
    githubOwner: 'nvd11',
    githubRepo: 'sms-flink-etl',
    workflowFile: 'trigger-nuc-batch-runner.yml',
    targetRef: 'main',
    defaultInputs: {
      target_env: 'finance',
      pipeline_stage: 'report',
      report_period: 'DAILY',
    },
  },
  metrics7d: {
    invocations: 7,
    successfulInvocations: 7,
    failedInvocations: 0,
    dailyBuckets: [
      { date: '2026-10-17', invocations: 1, failures: 0 },
      { date: '2026-10-18', invocations: 1, failures: 0 },
    ],
  },
  lastExecution: {
    runId: 1948271625,
    runNumber: 88,
    displayTitle: 'Batch ETL [report - DAILY]',
    attributionMethod: 'DISPLAY_TITLE',
    event: 'workflow_dispatch',
    status: 'completed',
    conclusion: 'success',
    startedAt: '2026-10-17T15:50:04Z',
    updatedAt: '2026-10-17T15:52:19Z',
    durationSeconds: 135,
    htmlUrl: 'https://github.com/nvd11/sms-flink-etl/actions/runs/1948271625',
  },
};

const tlsSchedule: SchedulePipelineView = {
  ...sampleSchedule,
  name: 'jppwl-tls-cert-renew-weekly',
  description: 'Weekly Sunday 04:00 Lets Encrypt TLS renewal',
  category: 'INFRA_TLS',
  state: 'DISABLED',
  routing: {
    ...sampleSchedule.routing,
    source: 'infra.tls-cert',
    detailType: 'renew-jppwl-tls',
    githubRepo: 'my-argocd-manifests',
    workflowFile: 'renew-jppwl-tls.yml',
  },
  lastExecution: {
    ...sampleSchedule.lastExecution!,
    runId: 202,
    runNumber: 12,
    status: 'in_progress',
    conclusion: null,
  },
};

describe('Dashboard App & Interactive Components Integration', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAuthService.getCurrentUser.mockResolvedValue({
      id: 'zhspkt3s6lm2',
      username: 'nvd11',
      email: 'nvd11@163.com',
      avatarUrl: 'https://github.com/nvd11.png',
      authMode: 'forward-auth',
    });

    mockSchedulerService.listSchedules.mockResolvedValue({
      summary: {
        totalSchedules: 2,
        enabledSchedules: 1,
        disabledSchedules: 1,
        runningOrQueuedJobs: 1,
        invocations7d: 920,
        successRate7d: 100.0,
        awsAccountId: '186004631963',
        awsRegion: 'ap-southeast-1',
      },
      items: [sampleSchedule, tlsSchedule],
    });

    mockSchedulerService.getScheduleRuns.mockResolvedValue({
      scheduleName: 'sms-flink-report-daily',
      githubRepo: 'nvd11/sms-flink-etl',
      workflowFile: 'trigger-nuc-batch-runner.yml',
      totalMatched: 3,
      runs: [
        sampleSchedule.lastExecution!,
        {
          ...sampleSchedule.lastExecution!,
          runId: 1948271626,
          runNumber: 87,
          attributionMethod: 'CRON_WINDOW',
          conclusion: 'failure',
        },
        {
          ...sampleSchedule.lastExecution!,
          runId: 1948271627,
          runNumber: 86,
          attributionMethod: 'SINGLE_OWNER',
          status: 'in_progress',
          conclusion: null,
        },
      ],
    });

    mockSchedulerService.getRunLogs.mockResolvedValue({
      scheduleName: 'sms-flink-report-daily',
      runId: 1948271625,
      jobId: 3029184712,
      jobName: 'trigger-nuc-batch',
      status: 'completed',
      conclusion: 'success',
      startedAt: '2026-10-17T15:50:04Z',
      completedAt: '2026-10-17T15:52:19Z',
      durationSeconds: 135,
      htmlUrl: 'https://github.com/nvd11/sms-flink-etl/actions/runs/1948271625/job/3029184712',
      jobs: [
        {
          jobId: 3029184712,
          name: 'trigger-nuc-batch',
          status: 'completed',
          conclusion: 'success',
          durationSeconds: 135,
        },
      ],
      steps: [
        {
          number: 1,
          name: 'Trigger Flink Daily Report on NUC',
          status: 'completed',
          conclusion: 'success',
          durationSeconds: 135,
        },
      ],
      truncated: false,
      totalLines: 3,
      logContent: '2026-10-17T15:50:05Z ##[group]Run job\n2026-10-17T15:51:00Z [WARN] Slow query\n2026-10-17T15:52:18Z [SUCCESS] Daily report pushed\n',
    });

    mockSchedulerService.triggerSchedule.mockResolvedValue({
      scheduleName: 'sms-flink-report-daily',
      mode: 'EVENTBRIDGE',
      eventId: 'evt-123',
      dispatchedAt: '2026-10-18T08:00:00Z',
      triggeredBy: 'nvd11',
      targetWorkflow: 'nvd11/sms-flink-etl/trigger-nuc-batch-runner.yml',
      recommendedPollDelayMs: 100,
    });

    mockSchedulerService.updateScheduleState.mockResolvedValue({
      currentState: 'DISABLED',
    });

    mockSchedulerService.updateScheduleConfig.mockResolvedValue({
      name: 'sms-flink-report-daily',
      scheduleExpression: 'cron(30 23 * * ? *)',
      timezone: 'Asia/Shanghai',
      humanReadableCron: 'Every day at 23:30 (Asia/Shanghai)',
      nextFireTime: '2026-10-18T15:30:00Z',
      state: 'ENABLED',
    });

    mockSchedulerService.previewCron.mockResolvedValue({
      valid: true,
      humanReadableCron: 'Every day at 23:30 (Asia/Shanghai)',
      upcomingFireTimes: ['2026-10-18T15:30:00Z'],
      errorMessage: null,
    });
  });

  test('renders KPI summary, schedule cards, filters, and toggles state', async () => {
    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('sms-flink-report-daily')).toBeInTheDocument();
    });

    expect(screen.getByText('nvd11')).toBeInTheDocument();
    expect(screen.getByText('100.0%')).toBeInTheDocument();

    // Filter by category tab
    fireEvent.click(screen.getByText(/Finance Flink ETL/i));
    expect(screen.getByText('sms-flink-report-daily')).toBeInTheDocument();
    expect(screen.queryByText('jppwl-tls-cert-renew-weekly')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText(/Infra & TLS/i));
    expect(screen.queryByText('sms-flink-report-daily')).not.toBeInTheDocument();
    expect(screen.getByText('jppwl-tls-cert-renew-weekly')).toBeInTheDocument();

    fireEvent.click(screen.getByText(/All \(2\)/i));

    // Search filter
    const searchInput = screen.getByLabelText('Search schedules');
    fireEvent.change(searchInput, { target: { value: 'daily' } });
    expect(screen.getByText('sms-flink-report-daily')).toBeInTheDocument();
    expect(screen.queryByText('jppwl-tls-cert-renew-weekly')).not.toBeInTheDocument();
    fireEvent.change(searchInput, { target: { value: '' } });

    // Toggle schedule state
    fireEvent.click(screen.getByLabelText('Toggle sms-flink-report-daily'));
    await waitFor(() => {
      expect(mockSchedulerService.updateScheduleState).toHaveBeenCalledWith(
        'sms-flink-report-daily',
        'DISABLED'
      );
    });

    // Quick trigger Run Now button
    fireEvent.click(screen.getByLabelText('Run Now sms-flink-report-daily'));
    await waitFor(() => {
      expect(mockSchedulerService.triggerSchedule).toHaveBeenCalledWith(
        'sms-flink-report-daily',
        { mode: 'EVENTBRIDGE' }
      );
    });

    // Filter by state select
    fireEvent.change(screen.getByLabelText('Filter by schedule state'), {
      target: { value: 'DISABLED' },
    });
    expect(screen.getByText('jppwl-tls-cert-renew-weekly')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Filter by schedule state'), {
      target: { value: 'ALL' },
    });

    // Toggle auto refresh and manual refresh in TopNavbar
    fireEvent.click(screen.getByTitle('Toggle background auto-refresh interval'));
    fireEvent.click(screen.getByTitle('Bypass backend cache and fetch fresh schedules'));
  });

  test('opens RunHistoryAndLogDrawer, filters terminal logs, and switches tail lines', async () => {
    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('sms-flink-report-daily')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByLabelText('History and Logs for sms-flink-report-daily'));

    await waitFor(() => {
      expect(screen.getByTestId('history-drawer')).toBeInTheDocument();
      expect(screen.getByText(/Daily report pushed/i)).toBeInTheDocument();
    });

    // Filter log lines inside TerminalLogViewer
    const filterInput = screen.getByLabelText('Filter log lines');
    fireEvent.change(filterInput, { target: { value: 'WARN' } });
    expect(screen.getByText(/Slow query/i)).toBeInTheDocument();
    expect(screen.queryByText(/Daily report pushed/i)).not.toBeInTheDocument();

    // Copy and Jump to bottom buttons
    Object.assign(navigator, {
      clipboard: { writeText: jest.fn().mockResolvedValue(undefined) },
    });
    fireEvent.click(screen.getByLabelText('Copy console logs'));
    fireEvent.click(screen.getByLabelText('Jump to bottom'));
    fireEvent.click(screen.getByLabelText('Refresh run history'));

    // Switch tail lines
    fireEvent.change(screen.getByLabelText('Tail lines limit'), { target: { value: '2000' } });
    await waitFor(() => {
      expect(mockSchedulerService.getRunLogs).toHaveBeenCalledWith(
        'sms-flink-report-daily',
        1948271625,
        2000
      );
    });

    // Click run item in drawer
    fireEvent.click(screen.getByTestId('run-item-1948271625'));

    // Click logout button in TopNavbar
    fireEvent.click(screen.getByTitle('Sign out of Logto SSO'));
    expect(mockAuthService.gatewayLogout).toHaveBeenCalled();

    fireEvent.click(screen.getByLabelText('Close drawer'));
    expect(screen.queryByTestId('history-drawer')).not.toBeInTheDocument();
  });

  test('opens TriggerModal, switches to GITHUB_DIRECT mode, and dispatches', async () => {
    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('sms-flink-report-daily')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByLabelText('Trigger with parameters sms-flink-report-daily'));
    expect(screen.getByTestId('trigger-modal')).toBeInTheDocument();

    // Switch to Mode B: GitHub Direct
    fireEvent.click(screen.getByTestId('mode-github-btn'));
    const periodInput = screen.getByLabelText('Input report_period');
    fireEvent.change(periodInput, { target: { value: 'WEEKLY' } });

    fireEvent.click(screen.getByTestId('confirm-trigger-btn'));

    await waitFor(() => {
      expect(mockSchedulerService.triggerSchedule).toHaveBeenCalledWith(
        'sms-flink-report-daily',
        expect.objectContaining({
          mode: 'GITHUB_DIRECT',
          ref: 'main',
          inputs: expect.objectContaining({ report_period: 'WEEKLY' }),
        })
      );
    });
  });

  test('opens CronEditModal, previews cron, and saves updated config', async () => {
    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('sms-flink-report-daily')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByLabelText('Edit cron for sms-flink-report-daily'));
    expect(screen.getByTestId('cron-edit-modal')).toBeInTheDocument();

    // Click preset chip
    fireEvent.click(screen.getByText('Every 10m'));
    const exprInput = screen.getByLabelText('Schedule Expression') as HTMLInputElement;
    expect(exprInput.value).toBe('rate(10 minutes)');

    await waitFor(() => {
      expect(mockSchedulerService.previewCron).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByTestId('save-cron-btn'));

    await waitFor(() => {
      expect(mockSchedulerService.updateScheduleConfig).toHaveBeenCalledWith(
        'sms-flink-report-daily',
        expect.objectContaining({
          scheduleExpression: 'rate(10 minutes)',
          timezone: 'UTC',
        })
      );
    });
  });
});
