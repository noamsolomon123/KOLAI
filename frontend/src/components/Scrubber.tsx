import { useCallback, useRef, useState } from 'react'
import type { Segment } from '../lib/types'
import { fmtTime } from '../lib/util'

interface Props {
  currentTime: number
  duration: number
  segments: Segment[]
  onSeek: (t: number) => void
  onScrub: (t: number | null) => void
}

// Glass progress track with a glowing thumb, segment-boundary tick marks,
// and drag-to-seek (pointer events, works on touch + mouse). The rail/fill
// thicken and the thumb grows while dragging for a satisfying grab.
export default function Scrubber({
  currentTime,
  duration,
  segments,
  onSeek,
  onScrub,
}: Props) {
  const trackRef = useRef<HTMLDivElement | null>(null)
  const [dragging, setDragging] = useState(false)
  const dur = duration > 0 ? duration : 1
  const pct = Math.min(100, Math.max(0, (currentTime / dur) * 100))

  const timeFromEvent = useCallback(
    (clientX: number) => {
      const el = trackRef.current
      if (!el) return 0
      const rect = el.getBoundingClientRect()
      const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
      return ratio * dur
    },
    [dur],
  )

  const onPointerDown = useCallback(
    (e: React.PointerEvent) => {
      e.currentTarget.setPointerCapture(e.pointerId)
      setDragging(true)
      const t = timeFromEvent(e.clientX)
      onScrub(t)
    },
    [timeFromEvent, onScrub],
  )

  const onPointerMove = useCallback(
    (e: React.PointerEvent) => {
      if (!dragging) return
      onScrub(timeFromEvent(e.clientX))
    },
    [dragging, timeFromEvent, onScrub],
  )

  const onPointerUp = useCallback(
    (e: React.PointerEvent) => {
      if (!dragging) return
      const t = timeFromEvent(e.clientX)
      setDragging(false)
      onScrub(null)
      onSeek(t)
    },
    [dragging, timeFromEvent, onScrub, onSeek],
  )

  return (
    <div className="scrubber">
      <div
        className="scrubber__track"
        data-dragging={dragging}
        ref={trackRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        role="slider"
        aria-label="seek"
        aria-valuemin={0}
        aria-valuemax={Math.round(dur)}
        aria-valuenow={Math.round(currentTime)}
        tabIndex={0}
      >
        <div className="scrubber__rail" />
        {segments.map((s, i) =>
          i === 0 ? null : (
            <span
              key={i}
              className="scrubber__tick"
              style={{ left: `${(s.start_s / dur) * 100}%` }}
            />
          ),
        )}
        <div className="scrubber__fill" style={{ width: `${pct}%` }} />
        <div
          className="scrubber__thumb"
          style={{
            left: `${pct}%`,
            transform: `translate(-50%, -50%) scale(${dragging ? 1.35 : 1})`,
          }}
        />
      </div>
      <div className="scrubber__time">
        <span>{fmtTime(currentTime)}</span>
        <span>{fmtTime(dur)}</span>
      </div>
    </div>
  )
}
