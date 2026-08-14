import { useEffect, useRef, useState } from 'react'
import { EventsOn, EventsOff } from '../wailsjs/runtime/runtime'
import { CaptureStatus, StartCapture, StopCapture } from '../wailsjs/go/main/App'

type CaptureState = 'stopped' | 'starting' | 'running' | 'error'


interface LogEntry {
  id: number
  level: string
  message: string
  time: string
}

const MAX_LOG_ENTRIES = 500

const STATUS_COLOR: Record<CaptureState, string> = {
  stopped: '#9ca3af',
  starting: '#f59e0b',
  running: '#22c55e',
  error: '#ef4444',
}

const LEVEL_COLOR: Record<string, string> = {
  info: '#9ca3af',
  warning: '#f59e0b',
  warn: '#f59e0b',
  error: '#ef4444',
  debug: '#60a5fa',
  trace: '#a78bfa',
}

let logIdCounter = 0

export function CaptureTab() {
  const [mode, setMode] = useState<'public' | 'private'>('public')
  const [status, setStatus] = useState<CaptureState>('stopped')
  const [logs, setLogs] = useState<LogEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const logBottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // Init status from backend
    CaptureStatus().then((s) => setStatus(s as CaptureState))

    // Listen for status changes
    EventsOn('capture:status', (state: string) => {
      setStatus(state as CaptureState)
    })

    // Listen for log entries
    EventsOn('log:entry', (entry: { level: string; message: string; time: string }) => {
      const newEntry: LogEntry = { id: logIdCounter++, ...entry }
      setLogs((prev) => {
        const next = [...prev, newEntry]
        return next.length > MAX_LOG_ENTRIES ? next.slice(next.length - MAX_LOG_ENTRIES) : next
      })
    })

    return () => {
      EventsOff('capture:status')
      EventsOff('log:entry')
    }
  }, [])

  // Auto-scroll log to bottom on new entries
  useEffect(() => {
    logBottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  const handleStart = async () => {
    setError(null)
    try {
      await StartCapture(mode)
    } catch (e) {
      setError(String(e))
    }
  }

  const isCapturing = status === 'running' || status === 'starting'
  const startLabel = status === 'starting' ? 'Starting…' : status === 'error' ? 'Retry Capture' : 'Start Capture'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, height: '100%' }}>
      {/* Mode selector */}
      <div style={{ display: 'flex', gap: 8 }}>
        {(['public', 'private'] as const).map((m) => (
          <button
            key={m}
            onClick={() => setMode(m)}
            disabled={isCapturing}
            style={{
              padding: '6px 18px',
              borderRadius: 6,
              border: mode === m ? '2px solid #6366f1' : '2px solid #374151',
              background: mode === m ? '#1e1b4b' : 'transparent',
              color: '#e5e7eb',
              cursor: 'pointer',
              fontWeight: mode === m ? 600 : 400,
              textTransform: 'capitalize',
            }}
          >
            {m}
          </button>
        ))}
      </div>

      {/* Status + Start/Stop */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span
          style={{
            display: 'inline-block',
            width: 12,
            height: 12,
            borderRadius: '50%',
            background: STATUS_COLOR[status],
            flexShrink: 0,
          }}
        />
        <span style={{ color: '#d1d5db', textTransform: 'capitalize', minWidth: 72 }}>{status}</span>
        {!isCapturing && (
          <button
            onClick={handleStart}
            disabled={status === 'starting'}
            style={{
              padding: '7px 20px',
              borderRadius: 6,
              border: 'none',
              background: status === 'error' ? '#78350f' : '#312e81',
              color: '#fff',
              cursor: 'pointer',
              fontWeight: 600,
            }}
          >
            {startLabel}
          </button>
        )}
        {isCapturing && (
          <span style={{ color: '#6b7280', fontSize: 12 }}>Restart app to stop capture</span>
        )}
      </div>

      {error && (
        <div style={{ color: '#fca5a5', fontSize: 13, background: '#450a0a', padding: '6px 10px', borderRadius: 4 }}>
          {error}
        </div>
      )}

      {/* Log window */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ color: '#9ca3af', fontSize: 13 }}>Logs ({logs.length})</span>
        <button
          onClick={() => setLogs([])}
          style={{
            padding: '3px 10px',
            borderRadius: 4,
            border: '1px solid #374151',
            background: 'transparent',
            color: '#9ca3af',
            cursor: 'pointer',
            fontSize: 12,
          }}
        >
          Clear
        </button>
      </div>

      <div
        style={{
          flex: 1,
          overflowY: 'auto',
          background: '#0f172a',
          borderRadius: 6,
          padding: '8px 10px',
          fontFamily: 'monospace',
          fontSize: 12,
          lineHeight: 1.6,
          minHeight: 200,
        }}
      >
        {logs.length === 0 ? (
          <span style={{ color: '#4b5563' }}>No log entries yet.</span>
        ) : (
          logs.map((entry) => {
            const t = new Date(entry.time)
            const ts = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}:${String(t.getSeconds()).padStart(2, '0')}`
            const color = LEVEL_COLOR[entry.level.toLowerCase()] ?? '#9ca3af'
            return (
              <div key={entry.id} style={{ display: 'flex', gap: 8, marginBottom: 2 }}>
                <span style={{ color: '#6b7280', flexShrink: 0 }}>{ts}</span>
                <span style={{ color, flexShrink: 0, minWidth: 44 }}>{entry.level.toUpperCase()}</span>
                <span style={{ color: '#d1d5db', wordBreak: 'break-all' }}>{entry.message}</span>
              </div>
            )
          })
        )}
        <div ref={logBottomRef} />
      </div>
    </div>
  )
}
