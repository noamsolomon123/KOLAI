import { useState } from 'react'
import { motion } from 'framer-motion'
import SettingsPanel from './SettingsPanel'

interface Props {
  station: string
  regenerating: boolean
  onRegenerate: () => void
}

// Top row: wordmark + pulsing LIVE tag, "new station" action, and settings gear.
export default function StationRow({ station, regenerating, onRegenerate }: Props) {
  const [settingsOpen, setSettingsOpen] = useState(false)

  return (
    <>
      <motion.header
        className="glass station"
        initial={{ opacity: 0, y: -12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
      >
        <div className="station__brand">
          <span className="wordmark">{station || 'רדיו AI'}</span>
          <span className="live">
            <span className="live__dot" />
            LIVE
          </span>
        </div>
        <div className="station__actions">
          <motion.button
            className="ghost-btn"
            data-busy={regenerating}
            onClick={onRegenerate}
            disabled={regenerating}
            whileTap={regenerating ? undefined : { scale: 0.94 }}
            whileHover={regenerating ? undefined : { scale: 1.03 }}
          >
            {regenerating ? 'מרנדרים…' : '🔁 תחנה חדשה'}
          </motion.button>
          <motion.button
            className="ghost-btn ghost-btn--icon"
            onClick={() => setSettingsOpen(true)}
            whileTap={{ scale: 0.9 }}
            whileHover={{ scale: 1.08 }}
            aria-label="הגדרות"
          >
            ⚙️
          </motion.button>
        </div>
      </motion.header>

      <SettingsPanel open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </>
  )
}