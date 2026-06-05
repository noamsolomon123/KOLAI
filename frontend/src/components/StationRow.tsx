import { motion } from 'framer-motion'

interface Props {
  station: string
  regenerating: boolean
  onRegenerate: () => void
}

// Top row: wordmark + pulsing LIVE tag, and the "new station" regenerate action.
export default function StationRow({ station, regenerating, onRegenerate }: Props) {
  return (
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
    </motion.header>
  )
}
