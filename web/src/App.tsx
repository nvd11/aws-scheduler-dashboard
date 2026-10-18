import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import {
  DashboardSummary,
  SchedulePipelineView,
  TriggerScheduleRequest,
  UpdateScheduleConfigRequest,
  UserProfile,
} from '@/types/scheduler';
import { authService } from '@/services/authService';
import { schedulerService } from '@/services/schedulerService';
import { TopNavbar } from '@/components/TopNavbar';
import { KpiSummaryBanner } from '@/components/KpiSummaryBanner';
import { ScheduleCard } from '@/components/ScheduleCard';
import { RunHistoryAndLogDrawer } from '@/components/RunHistoryAndLogDrawer';
import { TriggerModal } from '@/components/TriggerModal';
import { CronEditModal } from '@/components/CronEditModal';

export const App: React.FC = () => {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [schedules, setSchedules] = useState<SchedulePipelineView[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [autoRefreshInterval, setAutoRefreshInterval] = useState(30);
  const [nowMs, setNowMs] = useState(() => Date.now());

  const [categoryTab, setCategoryTab] = useState<'ALL' | 'FINANCE' | 'INFRA_TLS'>('ALL');
  const [stateFilter, setStateFilter] = useState<'ALL' | 'ENABLED' | 'DISABLED'>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  const [togglingMap, setTogglingMap] = useState<Record<string, boolean>>({});
  const [triggeringMap, setTriggeringMap] = useState<Record<string, boolean>>({});
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const [drawerSchedule, setDrawerSchedule] = useState<SchedulePipelineView | null>(null);
  const [triggerModalSchedule, setTriggerModalSchedule] = useState<SchedulePipelineView | null>(null);
  const [cronModalSchedule, setCronModalSchedule] = useState<SchedulePipelineView | null>(null);
  const [isSavingCron, setIsSavingCron] = useState(false);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage((cur) => (cur === msg ? null : cur)), 4000);
  };

  const fetchDashboardData = useCallback(async (forceRefresh = false) => {
    if (forceRefresh) {
      setIsRefreshing(true);
    }
    try {
      const data = await schedulerService.listSchedules(forceRefresh);
      setSummary(data.summary);
      setSchedules(data.items || []);
    } catch {
      showToast('Failed to load schedules from backend API');
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, []);

  useEffect(() => {
    authService.getCurrentUser().then(setUser);
    fetchDashboardData(false);
  }, [fetchDashboardData]);

  // 1-second live countdown ticker
  useEffect(() => {
    const tick = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(tick);
  }, []);

  // Auto-refresh polling
  useEffect(() => {
    if (autoRefreshInterval <= 0) return;
    const timer = setInterval(() => fetchDashboardData(false), autoRefreshInterval * 1000);
    return () => clearInterval(timer);
  }, [autoRefreshInterval, fetchDashboardData]);

  const cycleAutoRefresh = () => {
    setAutoRefreshInterval((cur) => (cur === 30 ? 15 : cur === 15 ? 0 : 30));
  };

  const handleToggleState = async (schedule: SchedulePipelineView) => {
    const nextState = schedule.state === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    setTogglingMap((prev) => ({ ...prev, [schedule.name]: true }));
    // Optimistic update
    setSchedules((prev) =>
      prev.map((item) => (item.name === schedule.name ? { ...item, state: nextState } : item))
    );
    try {
      await schedulerService.updateScheduleState(schedule.name, nextState);
      showToast(`${schedule.name} is now ${nextState}`);
      await fetchDashboardData(true);
    } catch {
      showToast(`Failed to toggle state for ${schedule.name}`);
      await fetchDashboardData(false);
    } finally {
      setTogglingMap((prev) => ({ ...prev, [schedule.name]: false }));
    }
  };

  const executeTrigger = async (scheduleName: string, req: TriggerScheduleRequest) => {
    setTriggeringMap((prev) => ({ ...prev, [scheduleName]: true }));
    try {
      const res = await schedulerService.triggerSchedule(scheduleName, req);
      showToast(`Triggered ${scheduleName} via ${res.mode}! Polling status...`);
      setTriggerModalSchedule(null);
      setTimeout(() => {
        fetchDashboardData(true);
      }, res.recommendedPollDelayMs || 4000);
    } catch {
      showToast(`Failed to trigger ${scheduleName}`);
    } finally {
      setTriggeringMap((prev) => ({ ...prev, [scheduleName]: false }));
    }
  };

  const handleSaveCron = async (name: string, payload: UpdateScheduleConfigRequest) => {
    setIsSavingCron(true);
    try {
      await schedulerService.updateScheduleConfig(name, payload);
      showToast(`Updated schedule configuration for ${name}`);
      setCronModalSchedule(null);
      await fetchDashboardData(true);
    } catch {
      showToast(`Failed to update schedule configuration for ${name}`);
    } finally {
      setIsSavingCron(false);
    }
  };

  const filteredSchedules = useMemo(() => {
    return schedules.filter((item) => {
      if (categoryTab === 'FINANCE' && !item.category.startsWith('FINANCE')) {
        return false;
      }
      if (categoryTab === 'INFRA_TLS' && item.category !== 'INFRA_TLS') {
        return false;
      }
      if (stateFilter !== 'ALL' && item.state !== stateFilter) {
        return false;
      }
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        const hay = `${item.name} ${item.description} ${item.routing?.source || ''} ${
          item.routing?.githubRepo || ''
        }`.toLowerCase();
        return hay.includes(q);
      }
      return true;
    });
  }, [schedules, categoryTab, stateFilter, searchQuery]);

  const nearestSchedule = useMemo(() => {
    const enabled = schedules
      .filter((s) => s.state === 'ENABLED' && s.nextFireTime)
      .sort((a, b) => Date.parse(a.nextFireTime!) - Date.parse(b.nextFireTime!));
    return enabled.length > 0 ? enabled[0] : null;
  }, [schedules]);

  const nearestCountdownSeconds = useMemo(() => {
    if (!nearestSchedule?.nextFireTime) return null;
    return Math.max(0, Math.floor((Date.parse(nearestSchedule.nextFireTime) - nowMs) / 1000));
  }, [nearestSchedule, nowMs]);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <TopNavbar
        user={user}
        region={summary?.awsRegion || 'ap-southeast-1'}
        accountId={summary?.awsAccountId || '186004631963'}
        isRefreshing={isRefreshing}
        autoRefreshInterval={autoRefreshInterval}
        onToggleAutoRefresh={cycleAutoRefresh}
        onManualRefresh={() => fetchDashboardData(true)}
      />

      {toastMessage && (
        <div
          role="status"
          data-testid="toast-banner"
          className="fixed bottom-5 right-5 z-50 rounded-xl border border-sky-500/40 bg-slate-900/95 px-4 py-2.5 text-xs font-medium text-sky-300 shadow-xl backdrop-blur"
        >
          {toastMessage}
        </div>
      )}

      <main className="mx-auto max-w-7xl space-y-6 px-4 py-6 sm:px-6 lg:px-8">
        <KpiSummaryBanner
          summary={summary}
          nearestScheduleName={nearestSchedule?.name || null}
          nearestCountdownSeconds={nearestCountdownSeconds}
        />

        {/* Filter & Search Bar */}
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-800/80 bg-slate-900/50 p-3">
          <div className="flex flex-wrap items-center gap-1.5">
            {(
              [
                { id: 'ALL', label: `All (${schedules.length})` },
                {
                  id: 'FINANCE',
                  label: `Finance Flink ETL (${
                    schedules.filter((s) => s.category.startsWith('FINANCE')).length
                  })`,
                },
                {
                  id: 'INFRA_TLS',
                  label: `Infra & TLS (${
                    schedules.filter((s) => s.category === 'INFRA_TLS').length
                  })`,
                },
              ] as const
            ).map((tab) => (
              <button
                key={tab.id}
                type="button"
                onClick={() => setCategoryTab(tab.id)}
                className={`rounded-lg px-3 py-1.5 text-xs font-medium transition ${
                  categoryTab === tab.id
                    ? 'bg-sky-600 text-white shadow'
                    : 'text-slate-400 hover:bg-slate-800 hover:text-slate-200'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="Filter by schedule state"
              value={stateFilter}
              onChange={(e) => setStateFilter(e.target.value as 'ALL' | 'ENABLED' | 'DISABLED')}
              className="rounded-lg border border-slate-700 bg-slate-950 px-2.5 py-1.5 text-xs text-slate-200"
            >
              <option value="ALL">State: ALL</option>
              <option value="ENABLED">ENABLED</option>
              <option value="DISABLED">DISABLED</option>
            </select>

            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-500" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search schedules or repos..."
                aria-label="Search schedules"
                className="rounded-lg border border-slate-700 bg-slate-950 pl-8 pr-3 py-1.5 text-xs text-slate-200 placeholder-slate-500 focus:border-sky-500 focus:outline-none"
              />
            </div>
          </div>
        </div>

        {/* Schedule Cards Grid */}
        {isLoading ? (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {[1, 2, 3, 4].map((n) => (
              <div
                key={n}
                className="h-64 animate-pulse rounded-2xl border border-slate-800 bg-slate-900/50 p-5"
              />
            ))}
          </div>
        ) : filteredSchedules.length === 0 ? (
          <div className="rounded-2xl border border-slate-800 bg-slate-900/40 p-12 text-center text-sm text-slate-400">
            No schedules match the current filter criteria.
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {filteredSchedules.map((schedule) => (
              <ScheduleCard
                key={schedule.name}
                schedule={schedule}
                nowMs={nowMs}
                isTogglingState={Boolean(togglingMap[schedule.name])}
                isTriggering={Boolean(triggeringMap[schedule.name])}
                onToggleState={handleToggleState}
                onQuickTrigger={(s) => executeTrigger(s.name, { mode: 'EVENTBRIDGE' })}
                onOpenTriggerModal={(s) => setTriggerModalSchedule(s)}
                onOpenCronModal={(s) => setCronModalSchedule(s)}
                onOpenHistoryDrawer={(s) => setDrawerSchedule(s)}
              />
            ))}
          </div>
        )}
      </main>

      <RunHistoryAndLogDrawer
        schedule={drawerSchedule}
        onClose={() => setDrawerSchedule(null)}
      />

      <TriggerModal
        schedule={triggerModalSchedule}
        isSubmitting={Boolean(triggerModalSchedule && triggeringMap[triggerModalSchedule.name])}
        onClose={() => setTriggerModalSchedule(null)}
        onSubmit={executeTrigger}
      />

      <CronEditModal
        schedule={cronModalSchedule}
        isSaving={isSavingCron}
        onClose={() => setCronModalSchedule(null)}
        onSave={handleSaveCron}
      />
    </div>
  );
};

export default App;
