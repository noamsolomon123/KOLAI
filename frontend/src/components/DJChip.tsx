import { AnimatePresence, motion } from 'framer-motion'
import type { Talk } from '../lib/types'
import { BEAT_ICON, BEAT_LABEL } from '../lib/util'

interface Props {
  talk: Talk | null
}

// During a talk window: glows with ON AIR + beat-type label (no spoken text shown).
// Idle: a quiet "now playing" state with a little equalizer.
export default function DJChip({ talk }: Props) {
  const active = !!talk
  const icon = talk ? BEAT_ICON[talk.beat] ?? '🎙️' : '🎙️'
  const beatLabel = talk ? BEAT_LABEL[talk.beat] ?? talk.beat : ''

  return (
    <motion.div
      className="glass djchip"
      data-active={active}
      layout
      transition={{ type: 'spring', stiffness: 320, damping: 30 }}
    >
      <div className="djchip__icon">{icon}</div>
      <div className="djchip__body">
        <AnimatePresence mode="wait" initial={false}>
          {active ? (
            <motion.div
              key={beatLabel}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.32, ease: [0.16, 1, 0.3, 1] }}
            >
              <div className="djchip__beat">ON AIR</div>
              <p className="djchip__text">{beatLabel || 'שידור חי'}</p>
            </motion.div>
          ) : (
            <motion.div
              key="idle"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.3 }}
            >
              <div className="djchip__beat">ON AIR</div>
              <p className="djchip__text djchip__text--idle">מתנגן עכשיו</p>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
      {!active && (
        <div className="eq" aria-hidden>
          <span />
          <span />
          <span />
          <span />
        </div>
      )}
    </motion.div>
  )
}