import {
  motion,
  useMotionTemplate,
  useMotionValue,
  useReducedMotion,
  useSpring,
  useTransform,
} from 'framer-motion'
import { useCallback, useMemo, useRef } from 'react'
import { paletteFor, hashString } from '../lib/util'

interface Props {
  title: string
  artist: string
  playing?: boolean
}

// A deterministic gradient "album cover" generated from the track title.
// Same title -> same artwork, every render. Gentle y-bob float + glossy sheen
// + a pointer/gyro parallax tilt for a tactile, 3D feel.
export default function CoverArt({ title, artist, playing = false }: Props) {
  const reduce = useReducedMotion()
  const ref = useRef<HTMLDivElement | null>(null)

  // Raw pointer position (-0.5 .. 0.5), smoothed by springs for buttery tilt.
  const px = useMotionValue(0)
  const py = useMotionValue(0)
  const sx = useSpring(px, { stiffness: 150, damping: 18, mass: 0.5 })
  const sy = useSpring(py, { stiffness: 150, damping: 18, mass: 0.5 })
  const rotateY = useTransform(sx, [-0.5, 0.5], [10, -10])
  const rotateX = useTransform(sy, [-0.5, 0.5], [-10, 10])
  // shift the sheen with the tilt so the gloss tracks the "light"
  const sheenX = useTransform(sx, [-0.5, 0.5], ['62%', '38%'])
  const sheenY = useTransform(sy, [-0.5, 0.5], ['62%', '38%'])
  const sheen = useMotionTemplate`radial-gradient(140% 120% at ${sheenX} ${sheenY}, rgba(255,255,255,0.34) 0%, rgba(255,255,255,0.06) 22%, transparent 50%)`

  const onMove = useCallback(
    (e: React.PointerEvent) => {
      if (reduce) return
      const el = ref.current
      if (!el) return
      const r = el.getBoundingClientRect()
      px.set((e.clientX - r.left) / r.width - 0.5)
      py.set((e.clientY - r.top) / r.height - 0.5)
    },
    [px, py, reduce],
  )
  const onLeave = useCallback(() => {
    px.set(0)
    py.set(0)
  }, [px, py])

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

  // Float only while playing (and not reduced-motion); paused = rest.
  const float = !reduce && playing ? [0, -8, 0] : 0

  return (
    <div className="cover-wrap">
      <motion.div
        ref={ref}
        className="cover"
        onPointerMove={onMove}
        onPointerLeave={onLeave}
        style={{ rotateX, rotateY }}
        initial={{ opacity: 0, scale: 0.96, y: 8 }}
        animate={{ opacity: 1, scale: 1, y: float }}
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
        <motion.div className="cover__sheen" style={{ background: sheen }} />
        <div className="cover__ring" />
      </motion.div>
    </div>
  )
}
