import React from 'react';
import { DashboardSummary } from '@/types/scheduler';
import { formatCountdown } from '@/utils/format';
import { Activity, CheckCircle2, Clock, Layers } from 'lucide-react';

interface KpiSummaryBannerProps {
  summary: DashboardSummary | null;
  nearestScheduleName: string | null;
  nearestCountdownSeconds: number | null;
}

export const KpiSummaryBanner: React.FC<KpiSummaryBannerProps> = ({
  summary,
  nearestScheduleName,
  nearestCountdownSeconds,
}) => {
  const total = summary?.totalSchedules ?? 0;
  const enabled = summary?.enabledSchedules ?? 0;
  const disabled = summary?.disabledSchedules ?? 0;
  const invocations7d = summary?.invocations7d ?? 0;
  const successRate = summary?.successRate7d ?? 100.0;
  const activeJobs = summary?.runningOrQueuedJobs ?? 0;

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <div className="rounded-xl border border-slate-800/80 bg-slate-900/60 p-4 backdrop-blur-md">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium uppercase tracking-wider text-slate-400">
            Managed Schedules
          </span>
          <div className="rounded-lg bg-sky-500/10 p-2 text-sky-400">
            <Layers className="h-4 w-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span className="text-2xl font-bold text-slate-100">{total}</span>
          <span className="text-xs text-slate-400">Total</span>
        </div>
        <div className="mt-2 flex items-center space-x-3 text-xs">
          <span className="inline-flex items-center text-emerald-400">
            <span className="mr-1.5 h-1.5 w-1.5 rounded-full bg-emerald-400" />
            {enabled} Enabled
          </span>
          <span className="inline-flex items-center text-slate-400">
            <span className="mr-1.5 h-1.5 w-1.5 rounded-full bg-slate-600" />
            {disabled} Paused
          </span>
        </div>
      </div>

      <div className="rounded-xl border border-slate-800/80 bg-slate-900/60 p-4 backdrop-blur-md">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium uppercase tracking-wider text-slate-400">
            7-Day Reliability
          </span>
          <div className="rounded-lg bg-emerald-500/10 p-2 text-emerald-400">
            <CheckCircle2 className="h-4 w-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span className="text-2xl font-bold text-emerald-400">
            {successRate.toFixed(1)}%
          </span>
          <span className="text-xs text-slate-400">Success Rate</span>
        </div>
        <div className="mt-2 text-xs text-slate-400">
          {invocations7d.toLocaleString()} CloudWatch invocations (7d)
        </div>
      </div>

      <div className="rounded-xl border border-slate-800/80 bg-slate-900/60 p-4 backdrop-blur-md">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium uppercase tracking-wider text-slate-400">
            Active Pipelines
          </span>
          <div
            className={`rounded-lg p-2 ${
              activeJobs > 0
                ? 'bg-sky-500/20 text-sky-400 animate-pulse'
                : 'bg-slate-800 text-slate-400'
            }`}
          >
            <Activity className="h-4 w-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span
            className={`text-2xl font-bold ${
              activeJobs > 0 ? 'text-sky-400' : 'text-slate-100'
            }`}
          >
            {activeJobs}
          </span>
          <span className="text-xs text-slate-400">In-Progress / Queued</span>
        </div>
        <div className="mt-2 text-xs text-slate-400">
          {activeJobs > 0
            ? 'GitHub Actions runners actively executing'
            : 'All downstream runners idle'}
        </div>
      </div>

      <div className="rounded-xl border border-slate-800/80 bg-slate-900/60 p-4 backdrop-blur-md">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium uppercase tracking-wider text-slate-400">
            Next Firing Window
          </span>
          <div className="rounded-lg bg-amber-500/10 p-2 text-amber-400">
            <Clock className="h-4 w-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span className="font-mono text-xl font-bold text-amber-400">
            {formatCountdown(nearestCountdownSeconds)}
          </span>
        </div>
        <div className="mt-2 truncate text-xs text-slate-400" title={nearestScheduleName || ''}>
          {nearestScheduleName ? `Target: ${nearestScheduleName}` : 'No enabled schedule'}
        </div>
      </div>
    </div>
  );
};
