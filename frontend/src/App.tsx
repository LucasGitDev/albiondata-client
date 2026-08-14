import { CaptureTab } from './CaptureTab'

function App() {
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
          fontWeight: 700,
          fontSize: 16,
          letterSpacing: '0.02em',
          color: '#a5b4fc',
        }}
      >
        Albion Data Client
      </header>
      <main style={{ flex: 1, padding: 24, display: 'flex', flexDirection: 'column' }}>
        <CaptureTab />
      </main>
    </div>
  )
}

export default App
