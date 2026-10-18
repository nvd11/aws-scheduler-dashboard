import React, { useMemo, useRef, useState } from 'react';
import { ArrowDownToLine, Check, Copy, Search, Terminal } from 'lucide-react';
import { stripAnsiCodes } from '@/utils/format';

interface TerminalLogViewerProps {
  logContent: string;
  truncated: boolean;
  totalLines: number;
  tailLines: number;
  isLoading: boolean;
  onChangeTailLines: (lines: number) => void;
}

export const TerminalLogViewer: React.FC<TerminalLogViewerProps> = ({
  logContent,
  truncated,
  totalLines,
  tailLines,
  isLoading,
  onChangeTailLines,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [copied, setCopied] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const lines = useMemo(() => {
    const clean = stripAnsiCodes(logContent || '');
    const rawLines = clean.split('\n');
    if (rawLines.length > 0 && rawLines[rawLines.length - 1] === '') {
      rawLines.pop();
    }
    if (!searchQuery.trim()) {
      return rawLines.map((text, idx) => ({ lineNumber: idx + 1, text }));
    }
    const q = searchQuery.toLowerCase();
    return rawLines
      .map((text, idx) => ({ lineNumber: idx + 1, text }))
      .filter((item) => item.text.toLowerCase().includes(q));
  }, [logContent, searchQuery]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(stripAnsiCodes(logContent || ''));
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      setCopied(false);
    }
  };

  const handleScrollBottom = () => {
    if (containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight;
    }
  };

  const getLineColorClass = (line: string): string => {
    const upper = line.toUpperCase();
    if (upper.includes('ERROR') || upper.includes('FAILURE') || upper.includes('❌')) {
      return 'text-rose-400 bg-rose-950/30';
    }
    if (upper.includes('WARN') || upper.includes('⚠️')) {
      return 'text-amber-300';
    }
    if (upper.includes('SUCCESS') || upper.includes('✅') || upper.includes('🎉')) {
      return 'text-emerald-400';
    }
    if (upper.includes('##[GROUP]') || upper.includes('🚀')) {
      return 'text-sky-300 font-semibold';
    }
    return 'text-slate-300';
  };

  return (
    <div className="flex flex-col rounded-xl border border-slate-800 bg-slate-950 overflow-hidden">
      {/* Terminal Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-slate-900/90 px-3 py-2">
        <div className="flex items-center gap-2 text-xs text-slate-300">
          <Terminal className="h-4 w-4 text-sky-400" />
          <span className="font-mono font-semibold">Console Output</span>
          <span className="text-slate-500">
            ({totalLines} lines{truncated ? ', truncated' : ''})
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <Search className="pointer-events-none absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-500" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Filter log lines..."
              aria-label="Filter log lines"
              className="rounded-lg border border-slate-700 bg-slate-950 pl-7 pr-2.5 py-1 text-xs text-slate-200 placeholder-slate-500 focus:border-sky-500 focus:outline-none"
            />
          </div>

          <select
            aria-label="Tail lines limit"
            value={tailLines}
            onChange={(e) => onChangeTailLines(Number(e.target.value))}
            className="rounded-lg border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-200 focus:border-sky-500 focus:outline-none"
          >
            <option value={500}>Tail 500</option>
            <option value={2000}>Tail 2000</option>
          </select>

          <button
            type="button"
            onClick={handleCopy}
            aria-label="Copy console logs"
            className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2 py-1 text-xs text-slate-200 hover:bg-slate-700"
          >
            {copied ? <Check className="h-3.5 w-3.5 text-emerald-400" /> : <Copy className="h-3.5 w-3.5" />}
            <span>{copied ? 'Copied' : 'Copy'}</span>
          </button>

          <button
            type="button"
            onClick={handleScrollBottom}
            aria-label="Jump to bottom"
            className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2 py-1 text-xs text-slate-200 hover:bg-slate-700"
          >
            <ArrowDownToLine className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {/* Monospace Log Body */}
      <div
        ref={containerRef}
        data-testid="terminal-log-body"
        className="max-h-[420px] min-h-[240px] overflow-y-auto p-3 font-mono text-xs leading-relaxed"
      >
        {isLoading ? (
          <div className="flex h-40 items-center justify-center text-slate-400">
            Loading console stream from GitHub Actions...
          </div>
        ) : lines.length === 0 ? (
          <div className="flex h-40 items-center justify-center text-slate-500">
            No matching log lines found.
          </div>
        ) : (
          lines.map((item) => (
            <div key={item.lineNumber} className={`flex gap-3 px-1 py-0.5 rounded ${getLineColorClass(item.text)}`}>
              <span className="w-8 shrink-0 select-none text-right text-slate-600">{item.lineNumber}</span>
              <span className="whitespace-pre-wrap break-all">{item.text}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
};
