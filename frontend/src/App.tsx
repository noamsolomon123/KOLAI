import { useCallback, useEffect, useMemo } from 'react'
import Background from './components/Background'
import StationRow from './components/StationRow'
import NowPlaying from './components/NowPlaying'
import UpNext from './components/UpNext'
import StateCard from './components/StateCard'
import { apiUrl } from './lib/api'
import { useShow } from './hooks/useShow'
import { useAudio } from './hooks/useAudio'
import {
  activeSegment,
  activeTalk,
  paletteFor,
  showDuration,
} from './lib/util'

export default function App() {
  const { show, status, refresh, regenerate, regenerating } = useShow()
  const audio = useAudio()

  const segments = show?.segments ?? []
  const talk = show?.talk ?? []

  const active = useMemo(
    () => activeSegment(segments, audio.currentTime),
    [segments, audio.currentTime],
  )
  const activeTalkItem = useMemo(
    () => activeTalk(talk, audio.currentTime),
    [talk, audio.currentTime],
  )
  const total = showDuration(show, audio.duration)
  const activeIndex = active?.index ?? -1
  const activeTitle = active?.seg.title ?? 'רדיו AI'

  // Drive the background mesh + accents from the current track's palette.
  useEffect(() => {
    const pal = paletteFor(activeTitle)
    const root = document.documentElement
    root.style.setProperty('--hue-a', String(pal.hueA))
    root.style.setProperty('--hue-b', String(pal.hueB))
    root.style.setProperty('--hue-c', String(pal.hueC))
  }, [activeTitle])

  // Drive the playback-glow intensity (cover + play button) from play state.
  useEffect(() => {
    document.documentElement.style.setProperty('--glow', audio.playing ? '1' : '0')
  }, [audio.playing])

  // prev / next seek to the previous / next segment's start_s.
  const onPrev = useCallback(() => {
    if (!segments.length) return
    const i = activeIndex < 0 ? 0 : activeIndex
    const cur = segments[i]
    // if we're more than ~3s into a song, prev restarts it; else go back one.
    if (audio.currentTime - cur.start_s > 3 || i === 0) {
      audio.seek(cur.start_s)
    } else {
      audio.seek(segments[i - 1].start_s)
    }
  }, [segments, activeIndex, audio])

  const onNext = useCallback(() => {
    if (!segments.length) return
    const i = activeIndex < 0 ? 0 : activeIndex
    if (i + 1 < segments.length) audio.seek(segments[i + 1].start_s)
    else audio.seek(total)
  }, [segments, activeIndex, audio, total])

  const onPickRow = useCallback(
    (startS: number) => {
      audio.seek(startS)
      if (!audio.playing) audio.play()
    },
    [audio],
  )

  // ---- non-ready states ----
  if (status === 'loading') {
    return (
      <div className="app">
        <Background />
        <div className="stage">
          <StateCard spinner skeleton title="מתחברים לתחנה…" sub="טוען את השידור החי" />
        </div>
      </div>
    )
  }
  if (status === 'empty') {
    return (
      <div className="app">
        <Background />
        <div className="stage">
          <StateCard
            emoji="📻"
            title="מכינים את התחנה…"
            sub="עוד אין שידור. אפשר לרנדר תחנה חדשה ולחזור בעוד רגע."
            action={{ label: '🔁 צור תחנה חדשה', onClick: regenerate }}
          />
        </div>
      </div>
    )
  }
  if (status === 'error' || !show) {
    return (
      <div className="app">
        <Background />
        <div className="stage">
          <StateCard
            emoji="⚠️"
            title="משהו השתבש"
            sub="לא הצלחנו לטעון את השידור. ננסה שוב?"
            action={{ label: 'נסה שוב', onClick: refresh }}
          />
        </div>
      </div>
    )
  }

  // ---- ready ----
  return (
    <div className="app">
      <Background />
      <audio ref={audio.audioRef} src={apiUrl('/api/audio')} preload="metadata" />
      <div className="stage">
        <StationRow
          station={show.station}
          regenerating={regenerating}
          onRegenerate={regenerate}
        />

        <NowPlaying
          segment={active?.seg ?? null}
          talk={activeTalkItem}
          currentTime={audio.currentTime}
          duration={total}
          segments={segments}
          playing={audio.playing}
          buffering={audio.buffering}
          onToggle={audio.toggle}
          onPrev={onPrev}
          onNext={onNext}
          onSeek={audio.seek}
          onScrub={audio.setScrub}
        />

        <UpNext
          segments={segments}
          activeIndex={activeIndex}
          onPick={onPickRow}
        />
      </div>
    </div>
  )
}
