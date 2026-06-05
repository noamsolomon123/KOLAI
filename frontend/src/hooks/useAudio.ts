import { useCallback, useEffect, useRef, useState } from 'react'

interface UseAudio {
  /** Callback ref — attach to <audio ref={audioRef} />. Works no matter when
   *  the element mounts (e.g. after a loading state), unlike a mount-only effect. */
  audioRef: (node: HTMLAudioElement | null) => void
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
  // `el` (state) drives the listener effect; `elRef` (mutable) backs imperative
  // play/pause/seek. A callback ref updates both the instant the node mounts.
  const [el, setEl] = useState<HTMLAudioElement | null>(null)
  const elRef = useRef<HTMLAudioElement | null>(null)
  const audioRef = useCallback((node: HTMLAudioElement | null) => {
    elRef.current = node
    setEl(node)
  }, [])

  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const scrubRef = useRef<number | null>(null)

  useEffect(() => {
    if (!el) return
    const onTime = () => {
      if (scrubRef.current == null) setCurrentTime(el.currentTime)
    }
    const onDur = () => setDuration(el.duration || 0)
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    const onWaiting = () => setBuffering(true)
    const onPlaying = () => {
      setBuffering(false)
      setPlaying(true)
    }
    const onEnded = () => setPlaying(false)
    el.addEventListener('timeupdate', onTime)
    el.addEventListener('durationchange', onDur)
    el.addEventListener('loadedmetadata', onDur)
    el.addEventListener('play', onPlay)
    el.addEventListener('pause', onPause)
    el.addEventListener('waiting', onWaiting)
    el.addEventListener('playing', onPlaying)
    el.addEventListener('ended', onEnded)
    // Sync state in case events fired before listeners attached.
    setPlaying(!el.paused)
    setDuration(el.duration || 0)
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
  }, [el])

  const play = useCallback(() => {
    elRef.current?.play().catch(() => setPlaying(false))
  }, [])
  const pause = useCallback(() => {
    elRef.current?.pause()
  }, [])
  const toggle = useCallback(() => {
    const node = elRef.current
    if (!node) return
    if (node.paused) play()
    else pause()
  }, [play, pause])

  const seek = useCallback((t: number) => {
    const node = elRef.current
    if (!node) return
    node.currentTime = Math.max(0, t)
    setCurrentTime(node.currentTime)
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
