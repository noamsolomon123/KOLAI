import { motion } from 'framer-motion'
import type { Segment, Talk } from '../lib/types'
import CoverArt from './CoverArt'
import DJChip from './DJChip'
import Scrubber from './Scrubber'
import TransportControls from './TransportControls'

interface Props {
  segment: Segment | null
  talk: Talk | null
  currentTime: number
  duration: number
  segments: Segment[]
  playing: boolean
  buffering: boolean
  onToggle: () => void
  onPrev: () => void
  onNext: () => void
  onSeek: (t: number) => void
  onScrub: (t: number | null) => void
}

export default function NowPlaying(props: Props) {
  const { segment } = props
  const title = segment?.title ?? 'רדיו AI'
  const artist = segment?.artist ?? 'התחנה משדרת'

  return (
    <>
      <CoverArt title={title} artist={artist} playing={props.playing} />

      <motion.div
        className="meta"
        key={title}
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
      >
        <h1 className="meta__title" dir="auto">
          {title}
        </h1>
        <p className="meta__artist" dir="auto">
          {artist}
        </p>
      </motion.div>

      <DJChip talk={props.talk} />

      <Scrubber
        currentTime={props.currentTime}
        duration={props.duration}
        segments={props.segments}
        onSeek={props.onSeek}
        onScrub={props.onScrub}
      />

      <TransportControls
        playing={props.playing}
        buffering={props.buffering}
        onToggle={props.onToggle}
        onPrev={props.onPrev}
        onNext={props.onNext}
      />
    </>
  )
}
