import { api, handleUnauthorizedRedirect } from '@/services/api';
import { authService } from '@/services/authService';
import { schedulerService } from '@/services/schedulerService';

describe('API & Services Suite', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('handleUnauthorizedRedirect builds Kong OAuth2 start URL', () => {
    expect(handleUnauthorizedRedirect('/scheduler/jobs')).toBe(
      '/oauth2/start?rd=%2Fscheduler%2Fjobs'
    );
    expect(handleUnauthorizedRedirect('/other')).toBe(
      '/oauth2/start?rd=%2Fscheduler%2F'
    );
  });

  test('authService fetches current user and handles errors gracefully', async () => {
    jest.spyOn(api, 'get').mockResolvedValueOnce({
      data: {
        code: 0,
        message: 'success',
        data: {
          id: 'u1',
          username: 'nvd11',
          email: 'nvd11@163.com',
          avatarUrl: 'https://github.com/nvd11.png',
          authMode: 'forward-auth',
        },
        timestamp: '2026-10-18T08:00:00Z',
      },
    });

    const user = await authService.getCurrentUser();
    expect(user?.username).toBe('nvd11');

    jest.spyOn(api, 'get').mockResolvedValueOnce({
      data: { code: 40100, message: 'unauthorized', data: null },
    });
    expect(await authService.getCurrentUser()).toBeNull();

    jest.spyOn(api, 'get').mockRejectedValueOnce(new Error('Network error'));
    expect(await authService.getCurrentUser()).toBeNull();
  });

  test('schedulerService calls all backend REST endpoints with expected params', async () => {
    const getSpy = jest.spyOn(api, 'get').mockResolvedValue({
      data: { code: 0, data: { items: [], summary: {}, runs: [], logContent: 'ok' } },
    });
    const postSpy = jest.spyOn(api, 'post').mockResolvedValue({
      data: { code: 0, data: { valid: true, eventId: 'e1' } },
    });
    const patchSpy = jest.spyOn(api, 'patch').mockResolvedValue({
      data: { code: 0, data: { currentState: 'DISABLED' } },
    });
    const putSpy = jest.spyOn(api, 'put').mockResolvedValue({
      data: { code: 0, data: { name: 'job1', state: 'ENABLED' } },
    });

    await schedulerService.listSchedules(true, 'ENABLED', 'flink');
    expect(getSpy).toHaveBeenCalledWith('/v1/schedules', {
      params: { refresh: true, state: 'ENABLED', q: 'flink' },
    });

    await schedulerService.listSchedules();
    await schedulerService.getScheduleRuns('sms-flink-report-daily');
    await schedulerService.getRunLogs('sms-flink-report-daily', 101);

    await schedulerService.getScheduleRuns('sms-flink-report-daily', 20, true);
    expect(getSpy).toHaveBeenCalledWith('/v1/schedules/sms-flink-report-daily/runs', {
      params: { limit: 20, refresh: true },
    });

    await schedulerService.getRunLogs('sms-flink-report-daily', 101, 500, 9001);
    expect(getSpy).toHaveBeenCalledWith('/v1/schedules/sms-flink-report-daily/runs/101/logs', {
      params: { tailLines: 500, jobId: 9001 },
    });

    await schedulerService.triggerSchedule('sms-flink-report-daily', {
      mode: 'EVENTBRIDGE',
    });
    expect(postSpy).toHaveBeenCalledWith('/v1/schedules/sms-flink-report-daily/trigger', {
      mode: 'EVENTBRIDGE',
    });

    await schedulerService.updateScheduleState('sms-flink-report-daily', 'DISABLED');
    expect(patchSpy).toHaveBeenCalledWith('/v1/schedules/sms-flink-report-daily/state', {
      state: 'DISABLED',
    });

    await schedulerService.updateScheduleConfig('sms-flink-report-daily', {
      scheduleExpression: 'cron(30 23 * * ? *)',
      timezone: 'Asia/Shanghai',
      description: 'Updated',
    });
    expect(putSpy).toHaveBeenCalled();

    await schedulerService.previewCron('cron(30 23 * * ? *)', 'Asia/Shanghai');
    expect(postSpy).toHaveBeenCalledWith('/v1/cron/preview', {
      scheduleExpression: 'cron(30 23 * * ? *)',
      timezone: 'Asia/Shanghai',
    });
  });
});
