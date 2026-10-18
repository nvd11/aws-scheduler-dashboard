export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null || seconds <= 0 || Number.isNaN(seconds)) {
    return '0s';
  }
  const s = Math.floor(seconds);
  if (s < 60) {
    return `${s}s`;
  }
  const mins = Math.floor(s / 60);
  const remSec = s % 60;
  if (mins < 60) {
    return remSec > 0 ? `${mins}m ${remSec}s` : `${mins}m`;
  }
  const hours = Math.floor(mins / 60);
  const remMin = mins % 60;
  return remMin > 0 ? `${hours}h ${remMin}m` : `${hours}h`;
}

export function formatCountdown(
  target: string | number | null | undefined,
  nowMs: number = Date.now()
): string {
  if (target == null || target === '') {
    return '—';
  }
  let diffSeconds: number;
  if (typeof target === 'number') {
    if (Number.isNaN(target) || target < 0) {
      return '—';
    }
    diffSeconds = Math.floor(target);
  } else {
    const targetMs = Date.parse(target);
    if (Number.isNaN(targetMs)) {
      return '—';
    }
    diffSeconds = Math.max(0, Math.floor((targetMs - nowMs) / 1000));
  }
  if (diffSeconds === 0) {
    return 'Due now';
  }
  const days = Math.floor(diffSeconds / 86400);
  const hours = Math.floor((diffSeconds % 86400) / 3600);
  const mins = Math.floor((diffSeconds % 3600) / 60);
  const secs = diffSeconds % 60;

  if (days > 0) {
    return `${days}d ${String(hours).padStart(2, '0')}h ${String(mins).padStart(2, '0')}m`;
  }
  if (hours > 0) {
    return `${String(hours).padStart(2, '0')}h ${String(mins).padStart(2, '0')}m ${String(secs).padStart(2, '0')}s`;
  }
  return `${String(mins).padStart(2, '0')}m ${String(secs).padStart(2, '0')}s`;
}

export function formatRelativeTime(isoString: string | null | undefined, nowMs: number = Date.now()): string {
  if (!isoString) {
    return 'Never';
  }
  const parsed = Date.parse(isoString);
  if (Number.isNaN(parsed)) {
    return isoString;
  }
  const deltaSec = Math.max(0, Math.floor((nowMs - parsed) / 1000));
  if (deltaSec < 60) {
    return `${deltaSec}s ago`;
  }
  const mins = Math.floor(deltaSec / 60);
  if (mins < 60) {
    return `${mins}m ago`;
  }
  const hours = Math.floor(mins / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

export function formatDateTimeCst(isoString: string | null | undefined): string {
  if (!isoString) {
    return '—';
  }
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) {
    return isoString;
  }
  return date.toLocaleString('sv-SE', {
    timeZone: 'Asia/Shanghai',
    hour12: false,
  }) + ' CST';
}

export function stripAnsiCodes(text: string): string {
  if (!text) {
    return '';
  }
  // eslint-disable-next-line no-control-regex
  return text.replace(/\x1B\[[0-9;]*[a-zA-Z]/g, '');
}
