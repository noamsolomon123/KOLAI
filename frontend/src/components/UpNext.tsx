import { motion } from 'framer-motion'
import type { Segment } from '../lib/types'
import { fmtTime, paletteFor } from '../lib/util'

interface Props {
  segments: Segment[]
  activeIndex: number
  onPick: (startS: number) => void
}

function thumbBg(title: string): string {
  const p = paletteFor(title)
  return [
    `radial-gradient(120% 120% at 20% 15%, ${p.a} 0%, transparent 60%)`,
    `radial-gradient(120% 120% at 85% 90%, ${p.c} 0%, transparent 60%)`,
    `linear-gradient(140deg, hsl(${p.hueB} 60% 26%), hsl(${p.hueA} 60% 22%))`,
  ].join(', ')
}

const list = {
  hidden: {},
  show: {
    transition: { staggerChildren: 0.05, delayChildren: 0.18 },
  },
}
const item = {
  hidden: { opacity: 0, y: 12 },
  show: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.42, ease: [0.16, 1, 0.3, 1] as const },
  },
}

// Glass sheet of upcoming songs. Tap a row to seek to its start_s.
// Rows fade/slide in with a gentle stagger; the active row is highlighted.
export default function UpNext({ segments, activeIndex, onPick }: Props) {
  return (
    <motion.section
      className="glass upnext"
      initial={{ opacity: 0, y: 18 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1], delay: 0.15 }}
    >
      <header className="upnext__head">
        <span className="upnext__title">רשימת השמעה</span>
        <span className="upnext__count">{segments.length} שירים</span>
      </header>
      <motion.div
        className="upnext__list"
        variants={list}
        initial="hidden"
        animate="show"
      >
        {segments.map((s, i) => {
          const isActive = i === activeIndex
          const glyph = (s.title || s.artist || '♪').trim().charAt(0) || '♪'
          return (
            <motion.button
              key={`${s.title}-${i}`}
              className="row"
              data-active={isActive}
              variants={item}
              onClick={() => onPick(s.start_s)}
              whileTap={{ scale: 0.985 }}
              whileHover={{ x: 2 }}
              transition={{ type: 'spring', stiffness: 500, damping: 30 }}
            >
              <span className="row__thumb" style={{ background: thumbBg(s.title) }}>
                <span className="row__thumb-glyph" dir="auto">
                  {glyph}
                </span>
              </span>
              <span className="row__main">
                <span className="row__title" dir="auto">
                  {s.title}
                </span>
                <span className="row__artist" dir="auto">
                  {s.artist}
                </span>
              </span>
              {isActive ? (
                <span className="eq row__playing" aria-label="מתנגן">
                  <span />
                  <span />
                  <span />
                  <span />
                </span>
              ) : (
                <span className="row__dur">{fmtTime(s.end_s - s.start_s)}</span>
              )}
            </motion.button>
          )
        })}
      </motion.div>
    </motion.section>
  )
}
