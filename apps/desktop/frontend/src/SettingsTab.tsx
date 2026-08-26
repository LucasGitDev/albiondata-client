import { useEffect, useState } from 'react'
import { GetSettings, SaveSettings } from '../wailsjs/go/main/App'

export function SettingsTab() {
  const [publicUrl, setPublicUrl] = useState('')
  const [privateUrl, setPrivateUrl] = useState('')
  const [defaults, setDefaults] = useState({ publicIngestBaseUrls: '', privateIngestBaseUrls: '' })
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    GetSettings().then((s) => {
      setDefaults(s)
    })
  }, [])

  const handleSave = async () => {
    setError(null)
    setSaved(false)
    try {
      await SaveSettings({
        publicIngestBaseUrls: publicUrl,
        privateIngestBaseUrls: privateUrl,
      })
      // Refresh defaults to reflect saved values
      const updated = await GetSettings()
      setDefaults(updated)
      setPublicUrl('')
      setPrivateUrl('')
      setSaved(true)
      setTimeout(() => setSaved(false), 3000)
    } catch (e) {
      setError(String(e))
    }
  }

  const inputStyle: React.CSSProperties = {
    width: '100%',
    padding: '7px 10px',
    borderRadius: 5,
    border: '1px solid #374151',
    background: '#111827',
    color: '#e5e7eb',
    fontFamily: 'monospace',
    fontSize: 13,
    boxSizing: 'border-box',
  }

  const labelStyle: React.CSSProperties = {
    color: '#9ca3af',
    fontSize: 12,
    marginBottom: 4,
    display: 'block',
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 560 }}>
      <div style={{ color: '#6b7280', fontSize: 12 }}>
        Override ingest endpoints. Leave blank to keep current value. Settings apply in-memory — reset on restart.
      </div>

      <div>
        <label style={labelStyle}>Public Ingest URL</label>
        <input
          style={inputStyle}
          value={publicUrl}
          onChange={(e) => setPublicUrl(e.target.value)}
          placeholder={defaults.publicIngestBaseUrls || 'https+pow://albion-online-data.com'}
          spellCheck={false}
        />
      </div>

      <div>
        <label style={labelStyle}>Private Ingest URL</label>
        <input
          style={inputStyle}
          value={privateUrl}
          onChange={(e) => setPrivateUrl(e.target.value)}
          placeholder={defaults.privateIngestBaseUrls || '(not set)'}
          spellCheck={false}
        />
        <div style={{ color: '#4b5563', fontSize: 11, marginTop: 4 }}>
          Private mode requires login — see Account tab.
        </div>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <button
          onClick={handleSave}
          style={{
            padding: '7px 20px',
            borderRadius: 6,
            border: 'none',
            background: '#312e81',
            color: '#fff',
            cursor: 'pointer',
            fontWeight: 600,
          }}
        >
          Save
        </button>
        {saved && <span style={{ color: '#22c55e', fontSize: 13 }}>Settings applied</span>}
        {error && <span style={{ color: '#f87171', fontSize: 13 }}>{error}</span>}
      </div>
    </div>
  )
}
