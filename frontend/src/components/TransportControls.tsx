import { motion } from 'framer-motion'

interface Props {
  playing: boolean
  buffering: boolean
  onToggle: () => void
  onPrev: () => void
  onNext: () => void
}

const spring = { type: 'spring' as const, stiffness: 520, damping: 24 }

function PrevIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M6 5a1 1 0 0 1 2 0v5.6l9.5-6.3A1 1 0 0 1 19 5v14a1 1 0 0 1-1.5.87L8 13.4V19a1 1 0 1 1-2 0V5z" />
    </svg>
  )
}
function NextIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M18 5a1 1 0 0 0-2 0v5.6L6.5 4.3A1 1 0 0 0 5 5v14a1 1 0 0 0 1.5.87L16 13.4V19a1 1 0 1 0 2 0V5z" />
    </svg>
  )
}
function PlayIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M7 4.8c0-.86.94-1.39 1.68-.94l11.4 7.02a1.1 1.1 0 0 1 0 1.88L8.68 19.78A1.1 1.1 0 0 1 7 18.84V4.8z" />
    </svg>
  )
}
function PauseIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <rect x="6" y="4.5" width="4.2" height="15" rx="1.6" />
      <rect x="13.8" y="4.5" width="4.2" height="15" rx="1.6" />
    </svg>
  )
}

export default function TransportControls({
  playing,
  buffering,
  onToggle,
  onPrev,
  onNext,
}: Props) {
  return (
    <div className="transport">
      <motion.button
        className="tbtn"
        onClick={onPrev}
        whileTap={{ scale: 0.86 }}
        transition={spring}
        aria-label="הקודם"
      >
        <PrevIcon />
      </motion.button>

      <motion.button
        className="tbtn tbtn--play"
        onClick={onToggle}
        whileTap={{ scale: 0.9 }}
        transition={spring}
        aria-label={playing ? 'השהה' : 'נגן'}
      >
        {buffering ? (
          <span className="spinner" style={{ width: 28, height: 28, borderWidth: 3 }} />
        ) : playing ? (
          <PauseIcon />
        ) : (
          <PlayIcon />
        )}
      </motion.button>

      <motion.button
        className="tbtn"
        onClick={onNext}
        whileTap={{ scale: 0.86 }}
        transition={spring}
        aria-label="הבא"
      >
        <NextIcon />
      </motion.button>
    </div>
  )
}
