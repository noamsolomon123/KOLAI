// Registers the app-shell service worker (public/sw.js) for PWA installability.
// No-ops outside browsers that support service workers (and in dev where it is
// generally unnecessary). Failures are swallowed: the app must work without it.
export function registerServiceWorker(): void {
  if (typeof window === 'undefined') return
  if (!('serviceWorker' in navigator)) return
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      /* best effort: PWA caching is an enhancement, not required */
    })
  })
}
