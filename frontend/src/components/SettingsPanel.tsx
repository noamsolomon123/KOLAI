import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { apiUrl } from '../lib/api'

type DJLevel = 'less' | 'normal' | 'more'

const LEVELS: { value: DJLevel; label: string }[] = [
  { value: 'less', label: 'פחות' },
  { value: 'normal', label: 'רגיל' },
  { value: 'more', label: 'יותר' },
]

interface Props {
  open: boolean
  onClose: () => void
}

export default function SettingsPanel({ open, onClose }: Props) {
  const [level, setLevel] = useState<DJLevel>('normal')
  const [saved, setSaved] = useState(false)

  async function handleLevel(l: DJLevel) {
    setLevel(l)
    setSaved(false)
    try {
      await fetch(apiUrl('/api/station/settings'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ level: l }),
      })
      setSaved(true)
      setTimeout(() => setSaved(false), 1800)
    } catch {
      // best-effort — optimistic update already applied
    }
  }

  return (
    <AnimatePresence>
      {open && (
        <>
          {/* Backdrop */}
          <motion.div
            className="settings-backdrop"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.22 }}
            onClick={onClose}
          />

          {/* Sheet */}
          <motion.div
            className="glass settings-sheet"
            role="dialog"
            aria-modal="true"
            aria-label="הגדרות"
            initial={{ opacity: 0, y: 28, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 20, scale: 0.97 }}
            transition={{ type: 'spring', stiffness: 340, damping: 32 }}
          >
            {/* Header */}
            <div className="settings-header">
              <span className="settings-title">הגדרות</span>
              <motion.button
                className="settings-close"
                onClick={onClose}
                whileTap={{ scale: 0.88 }}
                whileHover={{ scale: 1.08 }}
                aria-label="סגור"
              >
                ✕
              </motion.button>
            </div>

            {/* Station info */}
            <div className="settings-info">
              <span className="settings-info__name">רדיו AI</span>
              <span className="settings-info__badge">שידור חי</span>
            </div>

            <div className="settings-divider" />

            {/* DJ talk level */}
            <div className="settings-section">
              <div className="settings-label">כמה ה-DJ מדבר</div>
              <div className="settings-seg" role="group" aria-label="כמות דיבור">
                {LEVELS.map(({ value, label }) => (
                  <motion.button
                    key={value}
                    className="settings-seg__btn"
                    data-active={level === value}
                    onClick={() => handleLevel(value)}
                    whileTap={{ scale: 0.93 }}
                  >
                    {label}
                  </motion.button>
                ))}
              </div>
              <p className="settings-hint">
                {saved ? '✓ נשמר' : 'חל על השירים הבאים'}
              </p>
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  )
}