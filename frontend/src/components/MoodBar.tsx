import { useEffect, useRef, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { apiUrl } from '../lib/api'

interface MoodItem {
  key: string
  label: string
  emoji: string
}

const FALLBACK_MOODS: MoodItem[] = [
  { key: 'mix',        label: 'מיקס',   emoji: '🎚️' },
  { key: 'party',      label: 'מסיבה', emoji: '🎉' },
  { key: 'late_night', label: 'לילה',   emoji: '🌙' },
  { key: 'focus',      label: 'ריכוז',  emoji: '🎯' },
  { key: 'morning',    label: 'בוקר',   emoji: '☀️' },
]

export default function MoodBar() {
  const [moods, setMoods] = useState<MoodItem[]>(FALLBACK_MOODS)
  const [active, setActive] = useState<string>('mix')
  const [toast, setToast] = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Fetch moods list from backend on mount.
  useEffect(() => {
    fetch(apiUrl('/api/station/moods'))
      .then((r) => r.ok ? r.json() : Promise.reject(r.status))
      .then((data: { moods: MoodItem[] }) => {
        if (Array.isArray(data.moods) && data.moods.length) setMoods(data.moods)
      })
      .catch(() => { /* keep fallback */ })
  }, [])

  async function handleMood(key: string) {
    if (key === active) return
    setActive(key)
    // Show toast
    if (toastTimer.current) clearTimeout(toastTimer.current)
    setToast(true)
    toastTimer.current = setTimeout(() => setToast(false), 2400)
    // POST to backend (best-effort)
    try {
      await fetch(apiUrl('/api/station/mood'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mood: key }),
      })
    } catch {
      // optimistic update already applied
    }
  }

  return (
    <div className="moodbar-wrap">
      <motion.div
        className="moodbar glass"
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1], delay: 0.06 }}
        role="group"
        aria-label="בחר מצב רוח"
      >
        {moods.map((m) => (
          <motion.button
            key={m.key}
            className={['mood-chip', active === m.key ? 'mood-chip--active' : ''].join(' ').trim()}
            onClick={() => handleMood(m.key)}
            whileTap={{ scale: 0.88 }}
            whileHover={{ scale: active === m.key ? 1 : 1.07 }}
            transition={{ type: 'spring', stiffness: 420, damping: 26 }}
            aria-pressed={active === m.key}
          >
            <span className="mood-chip__emoji">{m.emoji}</span>
            <span className="mood-chip__label">{m.label}</span>
          </motion.button>
        ))}
      </motion.div>

      <AnimatePresence>
        {toast && (
          <motion.p
            className="mood-toast"
            role="status"
            aria-live="polite"
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
          >
            המצב יתחלף בשירים הבאים
          </motion.p>
        )}
      </AnimatePresence>
    </div>
  )
}