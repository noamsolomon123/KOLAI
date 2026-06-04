import { motion } from 'framer-motion'
import { useMemo } from 'react'
import { paletteFor, hashString } from '../lib/util'

interface Props {
  title: string
  artist: string
}

// A deterministic gradient "album cover" generated from the track title.
// Same title -> same artwork, every render. Gentle y-bob float + glossy sheen.
export default function CoverArt({ title, artist }: Props) {
  const { background, glyph, angle } = useMemo(() => {
    const pal = paletteFor(title)
    const h = hashString(title || artist || 'radio')
    const ang = h % 360
    const bg = [
      `radial-gradient(120% 120% at 18% 12%, ${pal.a} 0%, transparent 55%)`,
      `radial-gradient(120% 120% at 85% 25%, ${pal.b} 0%, transparent 52%)`,
      `radial-gradient(140% 140% at 60% 100%, ${pal.c} 0%, transparent 60%)`,
      `conic-gradient(from ${ang}deg at 50% 50%, hsl(${pal.hueA} 70% 30%), hsl(${pal.hueB} 70% 26%), hsl(${pal.hueC} 70% 28%), hsl(${pal.hueA} 70% 30%))`,
    ].join(', ')
    const first = (title || artist || '♪').trim().charAt(0) || '♪'
    return { background: bg, glyph: first, angle: ang }
  }, [title, artist])

  return (
    <div className="cover-wrap">
      <motion.div
        className="cover"
        initial={{ opacity: 0, scale: 0.96, y: 8 }}
        animate={{ opacity: 1, scale: 1, y: [0, -8, 0] }}
        transition={{
          opacity: { duration: 0.6, ease: [0.16, 1, 0.3, 1] },
          scale: { duration: 0.6, ease: [0.16, 1, 0.3, 1] },
          y: { duration: 6, repeat: Infinity, ease: 'easeInOut' },
        }}
      >
        <div className="cover__art" style={{ background }} data-angle={angle} />
        <div className="cover__rings" />
        <div className="cover__glyph" dir="auto">
          {glyph}
        </div>
        <div className="cover__sheen" />
        <div className="cover__ring" />
      </motion.div>
    </div>
  )
}
