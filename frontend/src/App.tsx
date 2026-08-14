import { useState } from 'react'
import { CaptureTab } from './CaptureTab'
import { SettingsTab } from './SettingsTab'

type Tab = 'capture' | 'settings'

const TABS: { id: Tab; label: string }[] = [
  { id: 'capture', label: 'Capture' },
  { id: 'settings', label: 'Settings' },
]

function App() {
  const [tab, setTab] = useState<Tab>('capture')

  return (
    <div
      style={{
        minHeight: '100vh',
        background: '#111827',
        color: '#f9fafb',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <header
        style={{
          padding: '14px 24px',
          borderBottom: '1px solid #1f2937',
          display: 'flex',
          alignItems: 'center',
          gap: 24,
        }}
      >
        <span style={{ fontWeight: 700, fontSize: 16, letterSpacing: '0.02em', color: '#a5b4fc' }}>
          Albion Data Client
        </span>
        <nav style={{ display: 'flex', gap: 4 }}>
          {TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              style={{
                padding: '5px 14px',
                borderRadius: 5,
                border: 'none',
                background: tab === t.id ? '#1e1b4b' : 'transparent',
                color: tab === t.id ? '#a5b4fc' : '#6b7280',
                cursor: 'pointer',
                fontWeight: tab === t.id ? 600 : 400,
                fontSize: 14,
              }}
            >
              {t.label}
            </button>
          ))}
        </nav>
      </header>
      <main style={{ flex: 1, padding: 24, display: 'flex', flexDirection: 'column' }}>
        {tab === 'capture' && <CaptureTab />}
        {tab === 'settings' && <SettingsTab />}
      </main>
    </div>
  )
}

export default App
