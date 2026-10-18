import React, { useEffect, useState } from 'react';
import { CheckCircle2, Save, X, XCircle } from 'lucide-react';
import {
  CronPreviewResponse,
  SchedulePipelineView,
  UpdateScheduleConfigRequest,
} from '@/types/scheduler';
import { schedulerService } from '@/services/schedulerService';
import { formatDateTimeCst } from '@/utils/format';

interface CronEditModalProps {
  schedule: SchedulePipelineView | null;
  isSaving: boolean;
  onClose: () => void;
  onSave: (name: string, payload: UpdateScheduleConfigRequest) => void;
}

const PRESETS = [
  { label: 'Every 10m', expr: 'rate(10 minutes)', tz: 'UTC' },
  { label: 'Every 4h CST', expr: 'cron(0 0,4,8,12,16,20 * * ? *)', tz: 'Asia/Shanghai' },
  { label: 'Daily 23:50 CST', expr: 'cron(50 23 * * ? *)', tz: 'Asia/Shanghai' },
  { label: 'Mon 09:00 CST', expr: 'cron(0 9 ? * MON *)', tz: 'Asia/Shanghai' },
];

export const CronEditModal: React.FC<CronEditModalProps> = ({
  schedule,
  isSaving,
  onClose,
  onSave,
}) => {
  const [expression, setExpression] = useState(schedule?.scheduleExpression || '');
  const [timezone, setTimezone] = useState(schedule?.timezone || 'Asia/Shanghai');
  const [description, setDescription] = useState(schedule?.description || '');
  const [preview, setPreview] = useState<CronPreviewResponse | null>(null);

  useEffect(() => {
    if (!schedule) return;
    setExpression(schedule.scheduleExpression);
    setTimezone(schedule.timezone);
    setDescription(schedule.description);
  }, [schedule]);

  useEffect(() => {
    if (!schedule || !expression.trim()) return;
    const timer = setTimeout(async () => {
      try {
        const res = await schedulerService.previewCron(expression.trim(), timezone);
        setPreview(res);
      } catch {
        setPreview({
          valid: false,
          humanReadableCron: null,
          upcomingFireTimes: [],
          errorMessage: 'Failed to validate expression',
        });
      }
    }, 200);
    return () => clearTimeout(timer);
  }, [schedule, expression, timezone]);

  if (!schedule) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (preview && !preview.valid) return;
    onSave(schedule.name, {
      scheduleExpression: expression.trim(),
      timezone,
      description,
      state: schedule.state,
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm" data-testid="cron-edit-modal">
      <div className="w-full max-w-lg rounded-2xl border border-slate-800 bg-slate-900 p-6 shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h2 className="text-base font-bold text-slate-100">
            Edit Schedule · <span className="text-amber-400">{schedule.name}</span>
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close cron modal"
            className="rounded-lg p-1 text-slate-400 hover:bg-slate-800 hover:text-slate-200"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="mt-4 space-y-4 text-xs">
          <div>
            <span className="block text-slate-400 mb-1.5">Quick Presets</span>
            <div className="flex flex-wrap gap-1.5">
              {PRESETS.map((p) => (
                <button
                  key={p.label}
                  type="button"
                  onClick={() => {
                    setExpression(p.expr);
                    setTimezone(p.tz);
                  }}
                  className="rounded-lg border border-slate-700 bg-slate-800/80 px-2.5 py-1 text-[11px] text-slate-200 hover:border-amber-500/50 hover:text-amber-300"
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div className="sm:col-span-2">
              <label className="block text-slate-400 mb-1">Schedule Expression</label>
              <input
                type="text"
                aria-label="Schedule Expression"
                value={expression}
                onChange={(e) => setExpression(e.target.value)}
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-amber-300 focus:border-sky-500 focus:outline-none"
              />
            </div>

            <div>
              <label className="block text-slate-400 mb-1">Timezone</label>
              <select
                aria-label="Schedule Timezone"
                value={timezone}
                onChange={(e) => setTimezone(e.target.value)}
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-200 focus:border-sky-500 focus:outline-none"
              >
                <option value="Asia/Shanghai">Asia/Shanghai</option>
                <option value="UTC">UTC</option>
                <option value="Asia/Singapore">Asia/Singapore</option>
                <option value="Europe/London">Europe/London</option>
                <option value="America/New_York">America/New_York</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-slate-400 mb-1">Description</label>
            <input
              type="text"
              aria-label="Schedule Description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-slate-200 focus:border-sky-500 focus:outline-none"
            />
          </div>

          {/* Live Validation & Next 5 Fire Times Preview */}
          {preview && (
            <div
              className={`rounded-xl border p-3 ${
                preview.valid
                  ? 'border-emerald-500/30 bg-emerald-500/5'
                  : 'border-rose-500/40 bg-rose-500/10'
              }`}
            >
              {preview.valid ? (
                <div>
                  <div className="flex items-center gap-1.5 font-semibold text-emerald-400">
                    <CheckCircle2 className="h-4 w-4" />
                    <span>{preview.humanReadableCron}</span>
                  </div>
                  {preview.upcomingFireTimes.length > 0 && (
                    <div className="mt-2 space-y-1 font-mono text-[11px] text-slate-300">
                      <div className="text-slate-400">Upcoming 5 Fire Times:</div>
                      {preview.upcomingFireTimes.map((t) => (
                        <div key={t}>• {formatDateTimeCst(t)} ({t})</div>
                      ))}
                    </div>
                  )}
                </div>
              ) : (
                <div className="flex items-center gap-1.5 text-rose-300">
                  <XCircle className="h-4 w-4 shrink-0" />
                  <span>{preview.errorMessage}</span>
                </div>
              )}
            </div>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg border border-slate-700 bg-slate-800 px-4 py-1.5 text-xs font-medium text-slate-300 hover:bg-slate-700"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSaving || (preview != null && !preview.valid)}
              data-testid="save-cron-btn"
              className="inline-flex items-center gap-1.5 rounded-lg bg-amber-500 px-4 py-1.5 text-xs font-semibold text-slate-950 hover:bg-amber-400 disabled:opacity-50"
            >
              <Save className="h-3.5 w-3.5" />
              <span>{isSaving ? 'Saving...' : 'Save Changes'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
