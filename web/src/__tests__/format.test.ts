import {
  formatCountdown,
  formatDateTimeCst,
  formatDuration,
  formatRelativeTime,
  stripAnsiCodes,
} from '@/utils/format';

describe('format utilities', () => {
  test('formatDuration formats seconds, minutes, and hours', () => {
    expect(formatDuration(null)).toBe('0s');
    expect(formatDuration(0)).toBe('0s');
    expect(formatDuration(-5)).toBe('0s');
    expect(formatDuration(45)).toBe('45s');
    expect(formatDuration(120)).toBe('2m');
    expect(formatDuration(135)).toBe('2m 15s');
    expect(formatDuration(3600)).toBe('1h');
    expect(formatDuration(3720)).toBe('1h 2m');
  });

  test('formatCountdown formats numeric seconds and ISO target timestamps', () => {
    expect(formatCountdown(null)).toBe('—');
    expect(formatCountdown('')).toBe('—');
    expect(formatCountdown('invalid-date')).toBe('—');
    expect(formatCountdown(-10)).toBe('—');
    expect(formatCountdown(0)).toBe('Due now');
    expect(formatCountdown(125)).toBe('02m 05s');
    expect(formatCountdown(3665)).toBe('01h 01m 05s');
    expect(formatCountdown(90060)).toBe('1d 01h 01m');

    const now = Date.parse('2026-10-18T08:00:00Z');
    expect(formatCountdown('2026-10-18T08:10:00Z', now)).toBe('10m 00s');
    expect(formatCountdown('2026-10-18T07:50:00Z', now)).toBe('Due now');
  });

  test('formatRelativeTime formats elapsed time accurately', () => {
    const now = Date.parse('2026-10-18T08:00:00Z');
    expect(formatRelativeTime(null, now)).toBe('Never');
    expect(formatRelativeTime('not-a-date', now)).toBe('not-a-date');
    expect(formatRelativeTime('2026-10-18T07:59:30Z', now)).toBe('30s ago');
    expect(formatRelativeTime('2026-10-18T07:45:00Z', now)).toBe('15m ago');
    expect(formatRelativeTime('2026-10-18T05:00:00Z', now)).toBe('3h ago');
    expect(formatRelativeTime('2026-10-16T08:00:00Z', now)).toBe('2d ago');
  });

  test('formatDateTimeCst and stripAnsiCodes clean and format strings', () => {
    expect(formatDateTimeCst(null)).toBe('—');
    expect(formatDateTimeCst('bad-date')).toBe('bad-date');
    expect(formatDateTimeCst('2026-10-18T15:50:00Z')).toContain('23:50:00 CST');

    expect(stripAnsiCodes('')).toBe('');
    expect(stripAnsiCodes('\u001B[36;1mHello\u001B[0m World')).toBe('Hello World');
  });
});
