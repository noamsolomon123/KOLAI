import { useCallback, useEffect, useState } from 'react'
import type { Show } from '../lib/types'

type Status = 'loading' | 'ready' | 'empty' | 'error'

interface UseShow {
  show: Show | null
  status: Status
  refresh: () => void
  regenerate: () => Promise<void>
  regenerating: boolean
}

// Fetches /api/show on mount. 404 => "empty" (no show yet, show a friendly
// waiting state). Exposes refresh() and regenerate() (POST /api/generate).
export function useShow(): UseShow {
  const [show, setShow] = useState<Show | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [regenerating, setRegenerating] = useState(false)

  const refresh = useCallback(() => {
    let cancelled = false
    setStatus((s) => (s === 'ready' ? s : 'loading'))
    fetch('/api/show', { cache: 'no-store' })
      .then((res) => {
        if (res.status === 404) {
          if (!cancelled) {
            setShow(null)
            setStatus('empty')
          }
          return null
        }
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json()
      })
      .then((data: Show | null) => {
        if (cancelled || data == null) return
        setShow(data)
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setStatus('error')
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const cleanup = refresh()
    return cleanup
  }, [refresh])

  const regenerate = useCallback(async () => {
    setRegenerating(true)
    try {
      await fetch('/api/generate', { method: 'POST' })
    } catch {
      /* best effort — backend just kicks off a render */
    }
    // keep the "rendering" affordance up briefly, then re-poll a few times.
    setTimeout(() => setRegenerating(false), 4000)
  }, [])

  return { show, status, refresh, regenerate, regenerating }
}
