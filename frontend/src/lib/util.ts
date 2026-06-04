import type { Segment, Show, Talk } from './types'

// Deterministic 32-bit hash (FNV-1a-ish) from a string. Stable across reloads.
export function hashString(str: string): number {
  let h = 2166136261
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

export interface Palette {
  hueA: number
  hueB: number
  hueC: number
  /** CSS hsl strings, ready to drop into gradients */
  a: string
  b: string
  c: string
  /** a soft glow color */
  glow: string
}

// Derive two-to-three harmonious hues from a track title. The mesh background
// and the cover artwork both read from this so they stay in lockstep.
export function paletteFor(title: string): Palette {
  const h = hashString(title || 'radio')
  const hueA = h % 360
  const hueB = (hueA + 40 + ((h >> 8) % 90)) % 360
  const hueC = (hueA + 200 + ((h >> 16) % 60)) % 360
  return {
    hueA,
    hueB,
    hueC,
    a: `hsl(${hueA} 85% 62%)`,
    b: `hsl(${hueB} 80% 58%)`,
    c: `hsl(${hueC} 78% 55%)`,
    glow: `hsl(${hueA} 90% 60%)`,
  }
}

// mm:ss
export function fmtTime(s: number): string {
  if (!isFinite(s) || s < 0) s = 0
  const m = Math.floor(s / 60)
  const sec = Math.floor(s % 60)
  return `${m}:${sec.toString().padStart(2, '0')}`
}

export function activeSegment(
  segments: Segment[],
  t: number,
): { seg: Segment; index: number } | null {
  for (let i = 0; i < segments.length; i++) {
    const s = segments[i]
    if (t >= s.start_s && t < s.end_s) return { seg: s, index: i }
  }
  // past the end: stick to the last segment
  if (segments.length && t >= segments[segments.length - 1].end_s) {
    return { seg: segments[segments.length - 1], index: segments.length - 1 }
  }
  return segments.length ? { seg: segments[0], index: 0 } : null
}

export function activeTalk(talk: Talk[], t: number): Talk | null {
  for (const item of talk) {
    if (t >= item.start_s && t < item.end_s) return item
  }
  return null
}

export const BEAT_ICON: Record<string, string> = {
  song: '🎙️',
  weather: '🌤️',
  news: '📰',
  topic: '💡',
  mashup: '🎛️',
}

export const BEAT_LABEL: Record<string, string> = {
  song: 'הצגת שיר',
  weather: 'מזג אוויר',
  news: 'חדשות',
  topic: 'על הפרק',
  mashup: 'מאש-אפ',
}

// total duration: prefer the show's declared length over the audio element's,
// which can lag while the stream loads.
export function showDuration(show: Show | null, audioDur: number): number {
  if (show && isFinite(show.duration_s) && show.duration_s > 0) return show.duration_s
  return isFinite(audioDur) && audioDur > 0 ? audioDur : 0
}
