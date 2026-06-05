import { motion } from 'framer-motion'

interface Props {
  emoji?: string
  spinner?: boolean
  /** show a shimmering skeleton (used for the "tuning in" loading state) */
  skeleton?: boolean
  title: string
  sub?: string
  action?: { label: string; onClick: () => void }
}

// Friendly full-screen state (loading / empty / error).
export default function StateCard({
  emoji,
  spinner,
  skeleton,
  title,
  sub,
  action,
}: Props) {
  return (
    <motion.div
      className="glass statecard"
      initial={{ opacity: 0, y: 14, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
    >
      {spinner ? (
        <div className="spinner" />
      ) : (
        <div className="statecard__emoji">{emoji}</div>
      )}
      <h2 className="statecard__title">{title}</h2>
      {sub && <p className="statecard__sub">{sub}</p>}
      {skeleton && (
        <div className="statecard__skeleton" aria-hidden>
          <div className="sk sk--lg sk--w70" />
          <div className="sk sk--w90" />
          <div className="sk sk--w50" />
        </div>
      )}
      {action && (
        <motion.button
          className="ghost-btn"
          onClick={action.onClick}
          whileTap={{ scale: 0.94 }}
          whileHover={{ scale: 1.03 }}
        >
          {action.label}
        </motion.button>
      )}
    </motion.div>
  )
}
