import { useCallback, useEffect, useRef, useState } from 'react'

interface UseAudio {
  audioRef: React.RefObject<HTMLAudioElement | null>
  currentTime: number
  duration: number
  playing: boolean
  buffering: boolean
  toggle: () => void
  play: () => void
  pause: () => void
  seek: (t: number) => void
  /** preview while dragging the scrubber without committing the seek yet */
  setScrub: (t: number | null) => void
}

// One <audio src="/api/audio">. Drives play/pause, seek, and time tracking.
// Respects mobile autoplay: playback only begins on the user's first tap.
export function useAudio(): UseAudio {
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const scrubRef = useRef<number | null>(null)

  useEffect(() => {
    const el = audioRef.current
    if (!el) return
    const onTime = () => {
      if (scrubRef.current == null) setCurrentTime(el.currentTime)
    }
    const onDur = () => setDuration(el.duration || 0)
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    const onWaiting = () => setBuffering(true)
    const onPlaying = () => setBuffering(false)
    const onEnded = () => setPlaying(false)
    el.addEventListener('timeupdate', onTime)
    el.addEventListener('durationchange', onDur)
    el.addEventListener('loadedmetadata', onDur)
    el.addEventListener('play', onPlay)
    el.addEventListener('pause', onPause)
    el.addEventListener('waiting', onWaiting)
    el.addEventListener('playing', onPlaying)
    el.addEventListener('ended', onEnded)
    return () => {
      el.removeEventListener('timeupdate', onTime)
      el.removeEventListener('durationchange', onDur)
      el.removeEventListener('loadedmetadata', onDur)
      el.removeEventListener('play', onPlay)
      el.removeEventListener('pause', onPause)
      el.removeEventListener('waiting', onWaiting)
      el.removeEventListener('playing', onPlaying)
      el.removeEventListener('ended', onEnded)
    }
  }, [])

  const play = useCallback(() => {
    audioRef.current?.play().catch(() => setPlaying(false))
  }, [])
  const pause = useCallback(() => {
    audioRef.current?.pause()
  }, [])
  const toggle = useCallback(() => {
    const el = audioRef.current
    if (!el) return
    if (el.paused) play()
    else pause()
  }, [play, pause])

  const seek = useCallback((t: number) => {
    const el = audioRef.current
    if (!el) return
    el.currentTime = Math.max(0, t)
    setCurrentTime(el.currentTime)
  }, [])

  const setScrub = useCallback((t: number | null) => {
    scrubRef.current = t
    if (t != null) setCurrentTime(t)
  }, [])

  return {
    audioRef,
    currentTime,
    duration,
    playing,
    buffering,
    toggle,
    play,
    pause,
    seek,
    setScrub,
  }
}
