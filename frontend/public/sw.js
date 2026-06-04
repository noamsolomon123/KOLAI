// Minimal app-shell service worker for Radio AI (PWA install support).
//
// Strategy:
//  - /api/* and audio: ALWAYS go to the network (never cached). These are the
//    live show JSON and the streamed audio (Range requests); caching them would
//    break playback / serve stale shows.
//  - navigations (HTML): network-first, falling back to the cached shell when
//    offline so the installed app still opens.
//  - other same-origin static assets (JS/CSS/icons): cache-first.
const CACHE = 'radioai-shell-v1'
const SHELL = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
]

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)

  // Never intercept API or cross-origin (backend on LAN, fonts, etc.).
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return

  // Navigations: network-first with offline shell fallback.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => caches.match('/index.html').then((r) => r || caches.match('/')))
    )
    return
  }

  // Static assets: cache-first, then populate cache on miss.
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached
      return fetch(req).then((res) => {
        if (res.ok && res.type === 'basic') {
          const copy = res.clone()
          caches.open(CACHE).then((c) => c.put(req, copy))
        }
        return res
      })
    })
  )
})
