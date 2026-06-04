import { motion } from 'framer-motion'

interface Props {
  emoji?: string
  spinner?: boolean
  title: string
  sub?: string
  action?: { label: string; onClick: () => void }
}

// Friendly full-screen state (loading / empty / error).
export default function StateCard({ emoji, spinner, title, sub, action }: Props) {
  return (
    <motion.div
      className="glass statecard"
      initial={{ opacity: 0, y: 14, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
    >
      {spinner ? <div className="spinner" /> : <div className="statecard__emoji">{emoji}</div>}
      <h2 className="statecard__title">{title}</h2>
      {sub && <p className="statecard__sub">{sub}</p>}
      {action && (
        <button className="ghost-btn" onClick={action.onClick}>
          {action.label}
        </button>
      )}
    </motion.div>
  )
}
