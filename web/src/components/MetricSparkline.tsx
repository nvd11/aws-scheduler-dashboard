import React from 'react';
import { DailyMetricBucket } from '@/types/scheduler';

interface MetricSparklineProps {
  buckets: DailyMetricBucket[];
}

export const MetricSparkline: React.FC<MetricSparklineProps> = ({ buckets }) => {
  const items = buckets && buckets.length > 0 ? buckets : [];
  if (items.length === 0) {
    return <span className="text-xs text-slate-500">No 7d data</span>;
  }

  const maxVal = Math.max(1, ...items.map((b) => b.invocations));

  return (
    <div className="flex items-end gap-1 h-7" data-testid="metric-sparkline">
      {items.map((b) => {
        const heightPct = b.invocations === 0 ? 15 : Math.max(22, Math.round((b.invocations / maxVal) * 100));
        const hasFailure = b.failures > 0;
        const barColor =
          b.invocations === 0
            ? 'bg-slate-700'
            : hasFailure
            ? 'bg-rose-500'
            : 'bg-emerald-500/80';
        return (
          <div
            key={b.date}
            title={`${b.date}: ${b.invocations} invocations (${b.failures} failed)`}
            className="flex flex-col justify-end h-full w-2.5 bg-slate-800/60 rounded-sm overflow-hidden"
          >
            <div className={`w-full rounded-sm transition-all ${barColor}`} style={{ height: `${heightPct}%` }} />
          </div>
        );
      })}
    </div>
  );
};
