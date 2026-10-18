import {
  ApiResponse,
  CronPreviewResponse,
  RunLogDetailView,
  ScheduleListResponse,
  ScheduleRunsResponse,
  TriggerScheduleRequest,
  TriggerScheduleResult,
  UpdateScheduleConfigRequest,
  UpdateScheduleConfigResult,
} from '@/types/scheduler';
import { api } from './api';

export const schedulerService = {
  async listSchedules(refresh = false, state?: string, q?: string): Promise<ScheduleListResponse> {
    const params: Record<string, string | boolean> = { refresh };
    if (state && state !== 'ALL') {
      params.state = state;
    }
    if (q && q.trim()) {
      params.q = q.trim();
    }
    const res = await api.get<ApiResponse<ScheduleListResponse>>('/v1/schedules', { params });
    return res.data.data;
  },

  async getScheduleRuns(name: string, limit = 20, refresh = false): Promise<ScheduleRunsResponse> {
    const res = await api.get<ApiResponse<ScheduleRunsResponse>>(`/v1/schedules/${encodeURIComponent(name)}/runs`, {
      params: { limit, refresh },
    });
    return res.data.data;
  },

  async getRunLogs(name: string, runId: number, tailLines = 500, jobId?: number): Promise<RunLogDetailView> {
    const params: Record<string, number> = { tailLines };
    if (jobId != null) {
      params.jobId = jobId;
    }
    const res = await api.get<ApiResponse<RunLogDetailView>>(
      `/v1/schedules/${encodeURIComponent(name)}/runs/${runId}/logs`,
      { params }
    );
    return res.data.data;
  },

  async triggerSchedule(name: string, payload: TriggerScheduleRequest): Promise<TriggerScheduleResult> {
    const res = await api.post<ApiResponse<TriggerScheduleResult>>(
      `/v1/schedules/${encodeURIComponent(name)}/trigger`,
      payload
    );
    return res.data.data;
  },

  async updateScheduleState(name: string, state: 'ENABLED' | 'DISABLED'): Promise<{ currentState: string }> {
    const res = await api.patch<ApiResponse<{ currentState: string }>>(
      `/v1/schedules/${encodeURIComponent(name)}/state`,
      { state }
    );
    return res.data.data;
  },

  async updateScheduleConfig(
    name: string,
    payload: UpdateScheduleConfigRequest
  ): Promise<UpdateScheduleConfigResult> {
    const res = await api.put<ApiResponse<UpdateScheduleConfigResult>>(
      `/v1/schedules/${encodeURIComponent(name)}`,
      payload
    );
    return res.data.data;
  },

  async previewCron(scheduleExpression: string, timezone: string): Promise<CronPreviewResponse> {
    const res = await api.post<ApiResponse<CronPreviewResponse>>('/v1/cron/preview', {
      scheduleExpression,
      timezone,
    });
    return res.data.data;
  },
};
