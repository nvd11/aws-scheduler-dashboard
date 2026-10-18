import React from 'react';
import { UserProfile } from '@/types/scheduler';
import { authService } from '@/services/authService';
import { Activity, Clock, LogOut, RefreshCw } from 'lucide-react';

interface TopNavbarProps {
  user: UserProfile | null;
  region: string;
  accountId: string;
  isRefreshing: boolean;
  autoRefreshInterval: number;
  onToggleAutoRefresh: () => void;
  onManualRefresh: () => void;
}

export const TopNavbar: React.FC<TopNavbarProps> = ({
  user,
  region,
  accountId,
  isRefreshing,
  autoRefreshInterval,
  onToggleAutoRefresh,
  onManualRefresh,
}) => {
  return (
    <header className="sticky top-0 z-30 border-b border-slate-800 bg-slate-950/80 backdrop-blur-md">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3 sm:px-6 lg:px-8">
        <div className="flex items-center space-x-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-amber-500 to-orange-600 shadow-md shadow-orange-950/50">
            <Clock className="h-5 w-5 text-slate-950" />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h1 className="text-base font-semibold text-slate-100 sm:text-lg">
                AWS Scheduler Control Plane
              </h1>
              <span className="hidden items-center rounded-md bg-slate-800/80 px-2 py-0.5 text-xs font-medium text-slate-300 ring-1 ring-inset ring-slate-700/50 sm:inline-flex">
                v1.0
              </span>
            </div>
            <div className="flex items-center space-x-2 text-xs text-slate-400">
              <span className="inline-flex items-center text-amber-400">
                <span className="mr-1.5 h-1.5 w-1.5 rounded-full bg-amber-400 animate-pulse" />
                {region}
              </span>
              <span>•</span>
              <span className="font-mono text-slate-400">Account: {accountId}</span>
            </div>
          </div>
        </div>

        <div className="flex items-center space-x-2 sm:space-x-4">
          <button
            type="button"
            onClick={onToggleAutoRefresh}
            title="Toggle background auto-refresh interval"
            className="flex items-center space-x-1.5 rounded-lg border border-slate-800 bg-slate-900/60 px-2.5 py-1.5 text-xs font-medium text-slate-300 transition hover:bg-slate-800 hover:text-slate-100"
          >
            <Activity className="h-3.5 w-3.5 text-sky-400" />
            <span className="hidden sm:inline">Auto:</span>
            <span>{autoRefreshInterval > 0 ? `${autoRefreshInterval}s` : 'Off'}</span>
          </button>

          <button
            type="button"
            onClick={onManualRefresh}
            disabled={isRefreshing}
            title="Bypass backend cache and fetch fresh schedules"
            className="flex items-center space-x-1.5 rounded-lg border border-slate-800 bg-slate-900/60 px-2.5 py-1.5 text-xs font-medium text-slate-300 transition hover:bg-slate-800 hover:text-slate-100 disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 text-slate-400 ${isRefreshing ? 'animate-spin text-sky-400' : ''}`} />
            <span className="hidden sm:inline">Refresh</span>
          </button>

          {user && (
            <div className="flex items-center space-x-3 border-l border-slate-800 pl-2 sm:pl-4">
              <div className="flex items-center space-x-2">
                <img
                  src={user.avatarUrl || `https://github.com/${user.username}.png`}
                  alt={user.username}
                  className="h-8 w-8 rounded-full border border-slate-700 bg-slate-800 object-cover"
                  onError={(e) => {
                    (e.target as HTMLImageElement).src = 'https://github.com/github.png';
                  }}
                />
                <div className="hidden flex-col text-left text-xs md:flex">
                  <span className="font-semibold text-slate-200">{user.username}</span>
                  <span className="truncate text-slate-500 max-w-[120px]">{user.email || 'SSO Authenticated'}</span>
                </div>
              </div>

              <button
                type="button"
                onClick={() => authService.gatewayLogout()}
                title="Sign out of Logto SSO"
                className="rounded-lg p-1.5 text-slate-400 transition hover:bg-rose-500/10 hover:text-rose-400"
              >
                <LogOut className="h-4 w-4" />
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
};
