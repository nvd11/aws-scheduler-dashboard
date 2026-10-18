import React, { useEffect, useState } from 'react';
import { Play, Send, X } from 'lucide-react';
import { SchedulePipelineView, TriggerScheduleRequest } from '@/types/scheduler';

interface TriggerModalProps {
  schedule: SchedulePipelineView | null;
  isSubmitting: boolean;
  onClose: () => void;
  onSubmit: (scheduleName: string, request: TriggerScheduleRequest) => void;
}

export const TriggerModal: React.FC<TriggerModalProps> = ({
  schedule,
  isSubmitting,
  onClose,
  onSubmit,
}) => {
  const [mode, setMode] = useState<'EVENTBRIDGE' | 'GITHUB_DIRECT'>('EVENTBRIDGE');
  const [ref, setRef] = useState(schedule?.routing?.targetRef || 'main');
  const [inputs, setInputs] = useState<Record<string, string>>({
    ...(schedule?.routing?.defaultInputs || {}),
  });

  useEffect(() => {
    if (!schedule) return;
    setMode('EVENTBRIDGE');
    setRef(schedule.routing?.targetRef || 'main');
    setInputs({ ...(schedule.routing?.defaultInputs || {}) });
  }, [schedule]);

  if (!schedule) return null;

  const handleInputChange = (key: string, value: string) => {
    setInputs((prev) => ({ ...prev, [key]: value }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(schedule.name, {
      mode,
      ref,
      inputs: mode === 'GITHUB_DIRECT' ? inputs : undefined,
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm" data-testid="trigger-modal">
      <div className="w-full max-w-lg rounded-2xl border border-slate-800 bg-slate-900 p-6 shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h2 className="text-base font-bold text-slate-100">
            Manual Trigger · <span className="text-sky-400">{schedule.name}</span>
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close trigger modal"
            className="rounded-lg p-1 text-slate-400 hover:bg-slate-800 hover:text-slate-200"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="mt-4 space-y-4">
          <div className="grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => setMode('EVENTBRIDGE')}
              data-testid="mode-eventbridge-btn"
              className={`rounded-xl border p-3 text-left text-xs transition ${
                mode === 'EVENTBRIDGE'
                  ? 'border-sky-500 bg-sky-500/10 text-sky-200'
                  : 'border-slate-800 bg-slate-950/60 text-slate-400'
              }`}
            >
              <div className="font-semibold">Mode A: AWS EventBridge</div>
              <div className="mt-1 text-[11px] opacity-80">
                Emulates native schedule event on default bus (Full 3-hop test)
              </div>
            </button>

            <button
              type="button"
              onClick={() => setMode('GITHUB_DIRECT')}
              data-testid="mode-github-btn"
              className={`rounded-xl border p-3 text-left text-xs transition ${
                mode === 'GITHUB_DIRECT'
                  ? 'border-sky-500 bg-sky-500/10 text-sky-200'
                  : 'border-slate-800 bg-slate-950/60 text-slate-400'
              }`}
            >
              <div className="font-semibold">Mode B: GitHub Direct</div>
              <div className="mt-1 text-[11px] opacity-80">
                Dispatches directly to GitHub Actions with custom input overrides
              </div>
            </button>
          </div>

          {mode === 'EVENTBRIDGE' ? (
            <div className="rounded-xl border border-slate-800 bg-slate-950 p-3 font-mono text-xs text-slate-300">
              <div>EventBus: <span className="text-amber-300">{schedule.routing?.eventBusName || 'default'}</span></div>
              <div>Source: <span className="text-sky-300">{schedule.routing?.source}</span></div>
              <div>DetailType: <span className="text-emerald-300">{schedule.routing?.detailType}</span></div>
            </div>
          ) : (
            <div className="space-y-3 rounded-xl border border-slate-800 bg-slate-950 p-3 text-xs">
              <div>
                <label className="block text-slate-400 mb-1">Target Git Branch (ref)</label>
                <input
                  type="text"
                  value={ref}
                  onChange={(e) => setRef(e.target.value)}
                  aria-label="Target Git Branch"
                  className="w-full rounded-lg border border-slate-700 bg-slate-900 px-3 py-1.5 font-mono text-slate-100"
                />
              </div>

              {Object.keys(inputs).length > 0 && (
                <div className="space-y-2">
                  <div className="text-slate-400 font-semibold">Workflow Inputs Override</div>
                  {Object.entries(inputs).map(([k, v]) => (
                    <div key={k} className="flex items-center gap-2">
                      <span className="w-36 shrink-0 font-mono text-slate-300">{k}</span>
                      <input
                        type="text"
                        aria-label={`Input ${k}`}
                        value={v}
                        onChange={(e) => handleInputChange(k, e.target.value)}
                        className="flex-1 rounded-lg border border-slate-700 bg-slate-900 px-2.5 py-1 font-mono text-amber-300"
                      />
                    </div>
                  ))}
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
              disabled={isSubmitting}
              data-testid="confirm-trigger-btn"
              className="inline-flex items-center gap-1.5 rounded-lg bg-sky-600 px-4 py-1.5 text-xs font-semibold text-white hover:bg-sky-500 disabled:opacity-50"
            >
              {mode === 'EVENTBRIDGE' ? <Play className="h-3.5 w-3.5" /> : <Send className="h-3.5 w-3.5" />}
              <span>{isSubmitting ? 'Dispatching...' : 'Dispatch Now'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
