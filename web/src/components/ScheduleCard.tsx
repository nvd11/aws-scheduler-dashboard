import React from 'react';
import {
  ArrowRight,
  CheckCircle2,
  Clock,
  Edit3,
  ExternalLink,
  GitBranch,
  History,
  Loader2,
  Play,
  Sliders,
  XCircle,
} from 'lucide-react';
import { SchedulePipelineView } from '@/types/scheduler';
import { formatCountdown, formatDuration, formatRelativeTime } from '@/utils/format';
import { MetricSparkline } from './MetricSparkline';

interface ScheduleCardProps {
  schedule: SchedulePipelineView;
  nowMs: number;
  isTogglingState: boolean;
  isTriggering: boolean;
  onToggleState: (schedule: SchedulePipelineView) => void;
  onQuickTrigger: (schedule: SchedulePipelineView) => void;
  onOpenTriggerModal: (schedule: SchedulePipelineView) => void;
  onOpenCronModal: (schedule: SchedulePipelineView) => void;
  onOpenHistoryDrawer: (schedule: SchedulePipelineView) => void;
}

export const ScheduleCard: React.FC<ScheduleCardProps> = ({
  schedule,
  nowMs,
  isTogglingState,
  isTriggering,
  onToggleState,
  onQuickTrigger,
  onOpenTriggerModal,
  onOpenCronModal,
  onOpenHistoryDrawer,
}) => {
  const isEnabled = schedule.state === 'ENABLED';
  const routing = schedule.routing;
  const lastRun = schedule.lastExecution;
  const inputEntries = routing?.defaultInputs ? Object.entries(routing.defaultInputs) : [];

  const renderRunBadge = () => {
    if (!lastRun) {
      return <span className="text-xs text-slate-500">No recorded runs</span>;
    }
    if (lastRun.status === 'in_progress' || lastRun.status === 'queued') {
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full border border-sky-500/30 bg-sky-500/10 px-2.5 py-0.5 text-xs font-medium text-sky-400">
          <Loader2 className="h-3 w-3 animate-spin" />
          <span>Running #{lastRun.runNumber}</span>
        </span>
      );
    }
    if (lastRun.conclusion === 'success') {
      return (
        <span className="inline-flex items-center gap-1 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-0.5 text-xs font-medium text-emerald-400">
          <CheckCircle2 className="h-3 w-3" />
          <span>#{lastRun.runNumber} Success</span>
        </span>
      );
    }
    return (
      <span className="inline-flex items-center gap-1 rounded-full border border-rose-500/30 bg-rose-500/10 px-2.5 py-0.5 text-xs font-medium text-rose-400">
        <XCircle className="h-3 w-3" />
        <span>#{lastRun.runNumber} {lastRun.conclusion || 'Failed'}</span>
      </span>
    );
  };

  return (
    <div
      data-testid={`schedule-card-${schedule.name}`}
      className="flex flex-col justify-between rounded-2xl border border-slate-800/90 bg-slate-900/75 p-5 shadow-lg backdrop-blur-md transition hover:border-slate-700"
    >
      <div>
        {/* Top Row: State Badge, Name, Category & Toggle */}
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span
                className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${
                  isEnabled
                    ? 'border border-emerald-500/30 bg-emerald-500/10 text-emerald-400'
                    : 'border border-slate-700 bg-slate-800 text-slate-400'
                }`}
              >
                <span
                  className={`h-1.5 w-1.5 rounded-full ${
                    isEnabled ? 'bg-emerald-400' : 'bg-slate-500'
                  }`}
                />
                {schedule.state}
              </span>
              <span className="rounded-md border border-slate-700/80 bg-slate-800/70 px-2 py-0.5 text-[11px] font-mono text-slate-300">
                {schedule.category}
              </span>
            </div>
            <h3 className="mt-2 truncate text-base font-bold text-slate-100" title={schedule.name}>
              {schedule.name}
            </h3>
            {schedule.description && (
              <p className="mt-0.5 line-clamp-1 text-xs text-slate-400" title={schedule.description}>
                {schedule.description}
              </p>
            )}
          </div>

          <button
            type="button"
            role="switch"
            aria-checked={isEnabled}
            aria-label={`Toggle ${schedule.name}`}
            disabled={isTogglingState}
            onClick={() => onToggleState(schedule)}
            className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none disabled:opacity-50 ${
              isEnabled ? 'bg-emerald-500' : 'bg-slate-700'
            }`}
          >
            <span
              className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                isEnabled ? 'translate-x-5' : 'translate-x-0'
              }`}
            />
          </button>
        </div>

        {/* Cron & Next Fire Countdown Box */}
        <div className="mt-4 rounded-xl border border-slate-800 bg-slate-950/60 p-3">
          <div className="flex items-center justify-between gap-2">
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <code className="rounded bg-slate-800 px-1.5 py-0.5 font-mono text-amber-300">
                {schedule.scheduleExpression}
              </code>
              <span className="rounded bg-slate-800/80 px-1.5 py-0.5 text-[11px] text-sky-300">
                {schedule.timezone}
              </span>
            </div>
            <button
              type="button"
              onClick={() => onOpenCronModal(schedule)}
              title="Edit Cron & Timezone"
              aria-label={`Edit cron for ${schedule.name}`}
              className="rounded-lg p-1 text-slate-400 hover:bg-slate-800 hover:text-slate-200"
            >
              <Edit3 className="h-3.5 w-3.5" />
            </button>
          </div>
          <div className="mt-1.5 flex flex-wrap items-center justify-between gap-2 text-xs">
            <span className="text-slate-300">{schedule.humanReadableCron}</span>
            <span className="inline-flex items-center gap-1 font-mono text-amber-400">
              <Clock className="h-3 w-3" />
              Next: {isEnabled ? formatCountdown(schedule.nextFireTime, nowMs) : 'Paused'}
            </span>
          </div>
        </div>

        {/* 3-Hop Topology Bar */}
        {routing && (
          <div className="mt-3 rounded-xl border border-slate-800/80 bg-slate-950/40 p-3 text-xs">
            <div className="flex flex-wrap items-center gap-1.5 text-slate-300">
              <span className="rounded bg-indigo-500/10 border border-indigo-500/30 px-1.5 py-0.5 font-mono text-[11px] text-indigo-300">
                {routing.source || 'eventbridge'} / {routing.detailType || '*'}
              </span>
              <ArrowRight className="h-3 w-3 text-slate-500 shrink-0" />
              <span className="truncate font-mono text-[11px] text-slate-400" title={routing.matchedRuleName || ''}>
                {routing.matchedRuleName || 'unmatched-rule'}
              </span>
              <ArrowRight className="h-3 w-3 text-slate-500 shrink-0" />
              {routing.githubOwner && routing.githubRepo ? (
                <span className="inline-flex items-center gap-1 rounded bg-sky-500/10 border border-sky-500/30 px-1.5 py-0.5 font-mono text-[11px] text-sky-300">
                  <GitBranch className="h-3 w-3" />
                  {routing.githubOwner}/{routing.githubRepo} · {routing.workflowFile}
                </span>
              ) : (
                <span className="text-slate-500">No GitHub target</span>
              )}
            </div>

            {inputEntries.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {inputEntries.map(([k, v]) => (
                  <span
                    key={k}
                    className="rounded bg-slate-800/90 px-1.5 py-0.5 font-mono text-[10px] text-slate-300"
                  >
                    {k}=<strong className="text-amber-300">{v}</strong>
                  </span>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Last Execution & 7-Day Sparkline */}
        <div className="mt-3 flex items-center justify-between gap-3 border-t border-slate-800/80 pt-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              {renderRunBadge()}
              {lastRun && (
                <span className="text-xs text-slate-400">
                  {formatDuration(lastRun.durationSeconds)} · {formatRelativeTime(lastRun.startedAt, nowMs)}
                </span>
              )}
              {lastRun?.htmlUrl && (
                <a
                  href={lastRun.htmlUrl}
                  target="_blank"
                  rel="noreferrer"
                  title="Open run on GitHub"
                  className="text-slate-400 hover:text-sky-400"
                >
                  <ExternalLink className="h-3.5 w-3.5" />
                </a>
              )}
            </div>
            {lastRun?.displayTitle && (
              <p className="mt-1 truncate text-[11px] text-slate-400" title={lastRun.displayTitle}>
                {lastRun.displayTitle}
              </p>
            )}
          </div>

          <div className="flex flex-col items-end shrink-0">
            <MetricSparkline buckets={schedule.metrics7d?.dailyBuckets || []} />
            <span className="mt-1 text-[10px] text-slate-400 font-mono">
              7d: {schedule.metrics7d?.successfulInvocations ?? 0}/{schedule.metrics7d?.invocations ?? 0} OK
            </span>
          </div>
        </div>
      </div>

      {/* Action Footer */}
      <div className="mt-4 flex items-center justify-between gap-2 border-t border-slate-800/80 pt-3">
        <div className="inline-flex rounded-lg shadow-sm">
          <button
            type="button"
            disabled={isTriggering}
            onClick={() => onQuickTrigger(schedule)}
            aria-label={`Run Now ${schedule.name}`}
            className="inline-flex items-center gap-1.5 rounded-l-lg bg-sky-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-sky-500 disabled:opacity-50"
          >
            {isTriggering ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
            <span>Run Now</span>
          </button>
          <button
            type="button"
            disabled={isTriggering}
            onClick={() => onOpenTriggerModal(schedule)}
            title="Run with custom parameters"
            aria-label={`Trigger with parameters ${schedule.name}`}
            className="inline-flex items-center rounded-r-lg border-l border-sky-700 bg-sky-600/90 px-2 py-1.5 text-xs text-white transition hover:bg-sky-500 disabled:opacity-50"
          >
            <Sliders className="h-3.5 w-3.5" />
          </button>
        </div>

        <button
          type="button"
          onClick={() => onOpenHistoryDrawer(schedule)}
          aria-label={`History and Logs for ${schedule.name}`}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800/80 px-3 py-1.5 text-xs font-medium text-slate-200 transition hover:border-slate-600 hover:bg-slate-800"
        >
          <History className="h-3.5 w-3.5 text-sky-400" />
          <span>History & Logs</span>
        </button>
      </div>
    </div>
  );
};
