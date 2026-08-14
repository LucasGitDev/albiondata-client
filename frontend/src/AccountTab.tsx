import { useEffect, useState } from 'react'
import { EventsOn, EventsOff } from '../wailsjs/runtime/runtime'
import { IsLoggedIn, GetUserEmail, Login, Logout } from '../wailsjs/go/main/App'

export function AccountTab() {
  const [loggedIn, setLoggedIn] = useState(false)
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [expired, setExpired] = useState(false)

  useEffect(() => {
    IsLoggedIn().then(setLoggedIn)
    GetUserEmail().then(setEmail)

    EventsOn('auth:login', (e: string) => {
      setLoggedIn(true)
      setEmail(e)
      setExpired(false)
    })
    EventsOn('auth:logout', () => {
      setLoggedIn(false)
      setEmail('')
    })
    EventsOn('auth:expired', () => {
      setLoggedIn(false)
      setEmail('')
      setExpired(true)
    })

    return () => {
      EventsOff('auth:login')
      EventsOff('auth:logout')
      EventsOff('auth:expired')
    }
  }, [])

  const handleLogin = async () => {
    setError(null)
    setExpired(false)
    setLoading(true)
    try {
      await Login()
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  const handleLogout = async () => {
    setError(null)
    setLoading(true)
    try {
      await Logout()
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 480 }}>
      {expired && (
        <div style={{ color: '#fca5a5', background: '#450a0a', padding: '8px 12px', borderRadius: 6, fontSize: 13 }}>
          Session expired — please log in again to use private capture.
        </div>
      )}

      {loggedIn ? (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span
              style={{
                display: 'inline-block',
                width: 10,
                height: 10,
                borderRadius: '50%',
                background: '#22c55e',
                flexShrink: 0,
              }}
            />
            <span style={{ color: '#d1d5db', fontSize: 14 }}>Logged in as</span>
            <span style={{ color: '#a5b4fc', fontWeight: 600, fontSize: 14 }}>{email}</span>
          </div>

          <button
            onClick={handleLogout}
            disabled={loading}
            style={{
              padding: '7px 20px',
              borderRadius: 6,
              border: '1px solid #374151',
              background: 'transparent',
              color: '#9ca3af',
              cursor: loading ? 'not-allowed' : 'pointer',
              fontWeight: 500,
              fontSize: 14,
              width: 'fit-content',
            }}
          >
            {loading ? 'Logging out…' : 'Log out'}
          </button>
        </>
      ) : (
        <>
          <div style={{ color: '#6b7280', fontSize: 13 }}>
            Log in with Google to enable private data upload. Opens a browser window for authentication.
          </div>

          <button
            onClick={handleLogin}
            disabled={loading}
            style={{
              padding: '8px 22px',
              borderRadius: 6,
              border: 'none',
              background: loading ? '#1e1b4b' : '#312e81',
              color: loading ? '#6b7280' : '#fff',
              cursor: loading ? 'not-allowed' : 'pointer',
              fontWeight: 600,
              fontSize: 14,
              width: 'fit-content',
            }}
          >
            {loading ? 'Opening browser…' : 'Log in with Google'}
          </button>
        </>
      )}

      {error && (
        <div style={{ color: '#fca5a5', background: '#450a0a', padding: '8px 12px', borderRadius: 6, fontSize: 13 }}>
          {error}
        </div>
      )}
    </div>
  )
}
