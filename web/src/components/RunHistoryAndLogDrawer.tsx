import React, { useEffect, useState } from 'react';
import {
  CheckCircle2,
  ExternalLink,
  Loader2,
  RefreshCw,
  X,
  XCircle,
} from 'lucide-react';
import {
  ExecutionRecord,
  RunLogDetailView,
  SchedulePipelineView,
} from '@/types/scheduler';
import { schedulerService } from '@/services/schedulerService';
import { formatDateTimeCst, formatDuration } from '@/utils/format';
import { TerminalLogViewer } from './TerminalLogViewer';

interface RunHistoryAndLogDrawerProps {
  schedule: SchedulePipelineView | null;
  onClose: () => void;
}

export const RunHistoryAndLogDrawer: React.FC<RunHistoryAndLogDrawerProps> = ({
  schedule,
  onClose,
}) => {
  const [runs, setRuns] = useState<ExecutionRecord[]>([]);
  const [isLoadingRuns, setIsLoadingRuns] = useState(false);
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const [logDetail, setLogDetail] = useState<RunLogDetailView | null>(null);
  const [isLoadingLogs, setIsLoadingLogs] = useState(false);
  const [tailLines, setTailLines] = useState(500);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const loadRuns = async (forceRefresh = false) => {
    if (!schedule) return;
    setIsLoadingRuns(true);
    setErrorMsg(null);
    try {
      const res = await schedulerService.getScheduleRuns(schedule.name, 20, forceRefresh);
      const list = res.runs || [];
      setRuns(list);
      if (list.length > 0 && (selectedRunId == null || !list.some((r) => r.runId === selectedRunId))) {
        setSelectedRunId(list[0].runId);
      }
    } catch (e: unknown) {
      setErrorMsg(e instanceof Error ? e.message : 'Failed to load execution history');
    } finally {
      setIsLoadingRuns(false);
    }
  };

  const loadRunLogs = async (runId: number, linesLimit: number) => {
    if (!schedule) return;
    setIsLoadingLogs(true);
    try {
      const detail = await schedulerService.getRunLogs(schedule.name, runId, linesLimit);
      setLogDetail(detail);
    } catch (e: unknown) {
      setErrorMsg(e instanceof Error ? e.message : 'Failed to load run logs');
    } finally {
      setIsLoadingLogs(false);
    }
  };

  useEffect(() => {
    if (schedule) {
      setSelectedRunId(schedule.lastExecution?.runId ?? null);
      setLogDetail(null);
      loadRuns(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schedule?.name]);

  useEffect(() => {
    if (schedule && selectedRunId != null) {
      loadRunLogs(selectedRunId, tailLines);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schedule?.name, selectedRunId, tailLines]);

  if (!schedule) return null;

  const renderAttributionBadge = (method: string) => {
    if (method === 'DISPLAY_TITLE') {
      return (
        <span className="rounded bg-emerald-500/10 border border-emerald-500/30 px-1.5 py-0.5 text-[10px] font-mono text-emerald-300">
          Exact Title
        </span>
      );
    }
    if (method === 'CRON_WINDOW') {
      return (
        <span className="rounded bg-amber-500/10 border border-amber-500/30 px-1.5 py-0.5 text-[10px] font-mono text-amber-300">
          Cron Window
        </span>
      );
    }
    return (
      <span className="rounded bg-sky-500/10 border border-sky-500/30 px-1.5 py-0.5 text-[10px] font-mono text-sky-300">
        Single Target
      </span>
    );
  };

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/70 backdrop-blur-sm" data-testid="history-drawer">
      <div className="flex h-full w-full max-w-6xl flex-col border-l border-slate-800 bg-slate-950 shadow-2xl">
        {/* Drawer Header */}
        <div className="flex items-center justify-between border-b border-slate-800 px-6 py-4">
          <div>
            <h2 className="text-lg font-bold text-slate-100">
              Execution History & Logs · <span className="text-sky-400">{schedule.name}</span>
            </h2>
            <p className="text-xs text-slate-400 font-mono">
              Target Workflow: {schedule.routing?.githubOwner}/{schedule.routing?.githubRepo} ➔{' '}
              {schedule.routing?.workflowFile}
            </p>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => loadRuns(true)}
              aria-label="Refresh run history"
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${isLoadingRuns ? 'animate-spin text-sky-400' : ''}`} />
              <span>Refresh</span>
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close drawer"
              className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-slate-200"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        {errorMsg && (
          <div className="mx-6 mt-4 rounded-lg border border-rose-500/40 bg-rose-500/10 px-4 py-2 text-xs text-rose-300">
            {errorMsg}
          </div>
        )}

        {/* Drawer Split Content */}
        <div className="grid flex-1 min-h-0 grid-cols-1 gap-4 overflow-hidden p-6 lg:grid-cols-12">
          {/* Left Column: Attributed Runs List */}
          <div className="flex min-h-0 flex-col gap-2 lg:col-span-5">
            <h3 className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Attributed Workflow Runs ({runs.length})
            </h3>
            {isLoadingRuns ? (
              <div className="flex h-40 items-center justify-center text-xs text-slate-400">
                Loading workflow runs...
              </div>
            ) : runs.length === 0 ? (
              <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-6 text-center text-xs text-slate-500">
                No attributed runs found for this schedule.
              </div>
            ) : (
              <div className="flex-1 min-h-0 space-y-2 overflow-y-auto pr-1">
                {runs.map((run) => {
                  const isSelected = run.runId === selectedRunId;
                  return (
                    <div
                      key={run.runId}
                      role="button"
                      tabIndex={0}
                      data-testid={`run-item-${run.runId}`}
                      onClick={() => setSelectedRunId(run.runId)}
                      onKeyDown={(e) => e.key === 'Enter' && setSelectedRunId(run.runId)}
                      className={`cursor-pointer rounded-xl border p-3 transition ${
                        isSelected
                          ? 'border-sky-500/60 bg-sky-500/10'
                          : 'border-slate-800 bg-slate-900/60 hover:border-slate-700'
                      }`}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-100">
                          {run.status !== 'completed' ? (
                            <Loader2 className="h-3.5 w-3.5 animate-spin text-sky-400" />
                          ) : run.conclusion === 'success' ? (
                            <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                          ) : (
                            <XCircle className="h-3.5 w-3.5 text-rose-400" />
                          )}
                          <span>#{run.runNumber}</span>
                          <span className="truncate max-w-[160px]" title={run.displayTitle}>
                            {run.displayTitle}
                          </span>
                        </div>
                        {renderAttributionBadge(run.attributionMethod)}
                      </div>
                      <div className="mt-2 flex items-center justify-between text-[11px] text-slate-400">
                        <span>{formatDateTimeCst(run.startedAt)}</span>
                        <div className="flex items-center gap-2">
                          <span className="font-mono">{formatDuration(run.durationSeconds)}</span>
                          {run.htmlUrl && (
                            <a
                              href={run.htmlUrl}
                              target="_blank"
                              rel="noreferrer"
                              onClick={(e) => e.stopPropagation()}
                              className="text-slate-400 hover:text-sky-400"
                            >
                              <ExternalLink className="h-3 w-3" />
                            </a>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* Right Column: Steps Breakdown & Console Log Viewer */}
          <div className="flex min-h-0 flex-col gap-4 lg:col-span-7">
            {logDetail && logDetail.steps && logDetail.steps.length > 0 && (
              <div className="shrink-0 rounded-xl border border-slate-800 bg-slate-900/60 p-3">
                <div className="mb-2 flex items-center justify-between text-xs">
                  <span className="font-semibold text-slate-200">
                    Job: {logDetail.jobName} (#{logDetail.jobId})
                  </span>
                  <span className="font-mono text-slate-400">
                    Duration: {formatDuration(logDetail.durationSeconds)}
                  </span>
                </div>
                <div className="flex flex-wrap gap-2">
                  {logDetail.steps.map((step) => (
                    <div
                      key={step.number}
                      className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs ${
                        step.conclusion === 'failure'
                          ? 'border-rose-500/40 bg-rose-500/10 text-rose-300'
                          : step.conclusion === 'success'
                          ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                          : 'border-slate-700 bg-slate-800 text-slate-300'
                      }`}
                    >
                      <span>
                        {step.number}. {step.name}
                      </span>
                      <span className="font-mono text-[10px] opacity-80">
                        ({formatDuration(step.durationSeconds)})
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <TerminalLogViewer
              logContent={logDetail?.logContent || ''}
              truncated={logDetail?.truncated || false}
              totalLines={logDetail?.totalLines || 0}
              tailLines={tailLines}
              isLoading={isLoadingLogs}
              onChangeTailLines={(lines) => setTailLines(lines)}
            />
          </div>
        </div>
      </div>
    </div>
  );
};
