// Shapes returned by the FastAPI backend (GET /api/show).

export type Beat = 'song' | 'weather' | 'news' | 'topic' | 'mashup'

export interface Segment {
  type: 'song'
  title: string
  artist: string
  start_s: number
  end_s: number
}

export interface Talk {
  beat: Beat
  text: string
  start_s: number
  end_s: number
}

export interface Show {
  station: string
  dj: string
  duration_s: number
  generated_at: string
  segments: Segment[]
  talk: Talk[]
}
