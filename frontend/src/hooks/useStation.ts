import { useCallback, useEffect, useRef, useState } from 'react'
import type { Show } from '../lib/types'
import { apiUrl } from '../lib/api'

type Status = 'tuning' | 'ready' | 'error'

interface StationAudio {
  /** Callback ref - attach to <audio ref={audioRef} />. Same pattern as
   *  useAudio: listeners attach the instant the node mounts. */
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

interface UseStation {
  show: Show | null
  status: Status
  index: number
  audio: StationAudio
}

const STATION_NAME = 'רדיו AI'

// Block meta as returned by GET /api/station/block/{n}/meta.
interface BlockMeta {
  index: number
  duration_s: number
  segments: Show['segments']
  talk: Show['talk']
}

// Wrap a backend block's meta as a Show so the rest of the UI is unchanged.
function blockToShow(meta: BlockMeta): Show {
  return {
    station: STATION_NAME,
    dj: STATION_NAME,
    duration_s: meta.duration_s,
    generated_at: '',
    segments: meta.segments ?? [],
    talk: meta.talk ?? [],
  }
}

const blockUrl = (n: number) => apiUrl(`/api/station/block/${n}`)
const blockMetaUrl = (n: number) => apiUrl(`/api/station/block/${n}/meta`)

// Plays the ENDLESS station: rolling blocks (0,1,2,...) that the backend
// renders ahead. Wraps the CURRENT block as a Show and drives one <audio>
// element via a callback ref (same listener pattern as useAudio). On `ended`
// it advances to the next block, making playback endless.
export function useStation(): UseStation {
  // `el` (state) drives the listener effect; `elRef` (mutable) backs imperative
  // play/pause/seek. A callback ref updates both the instant the node mounts.
  const [el, setEl] = useState<HTMLAudioElement | null>(null)
  const elRef = useRef<HTMLAudioElement | null>(null)
  const audioRef = useCallback((node: HTMLAudioElement | null) => {
    elRef.current = node
    setEl(node)
  }, [])

  const [show, setShow] = useState<Show | null>(null)
  const [status, setStatus] = useState<Status>('tuning')
  const [index, setIndex] = useState(0)

  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const scrubRef = useRef<number | null>(null)

  // Cache of wrapped Shows by block index, so advancing is instant when the
  // next block was already prefetched.
  const cacheRef = useRef<Map<number, Show>>(new Map())
  // The index currently loaded into the <audio> element. Lets the `ended`
  // handler advance without going stale on the closure.
  const indexRef = useRef(0)

  // ---- fetch a block's meta; returns wrapped Show on 200, null while 202 ----
  const fetchMeta = useCallback(async (n: number): Promise<Show | null> => {
    const cached = cacheRef.current.get(n)
    if (cached) return cached
    const res = await fetch(blockMetaUrl(n), { cache: 'no-store' })
    if (res.status === 202) return null // still rendering
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const meta = (await res.json()) as BlockMeta
    const wrapped = blockToShow(meta)
    cacheRef.current.set(n, wrapped)
    return wrapped
  }, [])

  // ---- prefetch a block (also nudges the backend to render ahead) ----
  const prefetch = useCallback(
    (n: number) => {
      if (cacheRef.current.has(n)) return
      let tries = 0
      const tick = async () => {
        tries += 1
        try {
          const wrapped = await fetchMeta(n)
          if (wrapped) return // cached inside fetchMeta
        } catch {
          return // transient - stop trying
        }
        if (tries < 30) window.setTimeout(tick, 3000)
      }
      void tick()
    },
    [fetchMeta],
  )

  // ---- point the <audio> element at a block and load its wrapped Show ----
  const loadBlock = useCallback(
    async (n: number, autoplay: boolean) => {
      indexRef.current = n
      setIndex(n)
      const node = elRef.current
      if (node) {
        node.src = blockUrl(n)
        node.load()
      }
      // Use the cached meta if present; otherwise poll until it is ready.
      let wrapped = cacheRef.current.get(n) ?? null
      if (!wrapped) {
        setStatus('tuning')
        for (let i = 0; i < 60; i++) {
          try {
            wrapped = await fetchMeta(n)
          } catch {
            setStatus('error')
            return
          }
          if (wrapped) break
          if (indexRef.current !== n) return // moved on; abort
          await new Promise((r) => window.setTimeout(r, 2000))
        }
      }
      if (indexRef.current !== n || !wrapped) return
      setShow(wrapped)
      setStatus('ready')
      if (autoplay) elRef.current?.play().catch(() => setPlaying(false))
      prefetch(n + 1)
    },
    [fetchMeta, prefetch],
  )

  // ---- on mount: start the station, then load block 0 (no autoplay) ----
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        await fetch(apiUrl('/api/station/start'), { method: 'POST' })
      } catch {
        // best effort - the block endpoints also kick off rendering
      }
      if (cancelled) return
      // Do NOT autoplay block 0 (respect mobile autoplay; user taps play).
      void loadBlock(0, false)
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ---- attach media listeners to the current <audio> node (callback ref) ----
  useEffect(() => {
    if (!el) return
    // The <audio> only mounts once status flips to 'ready', AFTER loadBlock(0)
    // ran (when the element did not exist yet). Set the current block's src here
    // on mount, otherwise play() has no source and loads forever.
    if (!el.src) {
      el.src = blockUrl(indexRef.current)
    }
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
    const onLoaded = () => setDuration(el.duration || 0)
    const onEnded = () => {
      // Advance to the next block. Continuing playback after a user-initiated
      // play is allowed, so autoplay the next block.
      setCurrentTime(0)
      setDuration(0)
      void loadBlock(indexRef.current + 1, true)
    }
    el.addEventListener('timeupdate', onTime)
    el.addEventListener('durationchange', onDur)
    el.addEventListener('loadedmetadata', onLoaded)
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
      el.removeEventListener('loadedmetadata', onLoaded)
      el.removeEventListener('play', onPlay)
      el.removeEventListener('pause', onPause)
      el.removeEventListener('waiting', onWaiting)
      el.removeEventListener('playing', onPlaying)
      el.removeEventListener('ended', onEnded)
    }
  }, [el, loadBlock])

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
    show,
    status,
    index,
    audio: {
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
    },
  }
}