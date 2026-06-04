// Base URL for the backend API.
//
// Default ("") keeps SAME-ORIGIN behavior: when the built app is served by
// FastAPI (which mounts dist/ at /), all /api/* fetches and the <audio> src
// resolve against the page origin and need no proxy.
//
// For the packaged Android APK / installed PWA running on the phone, the app
// is loaded from the device (capacitor) or cached locally and must reach the
// PC backend over the LAN. Build with VITE_API_BASE set to the PC's address,
// e.g. VITE_API_BASE=http://10.100.102.123:8000
//
// Trailing slash is stripped so the joined path is always well-formed.
export const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '')

/** Build a fully-qualified API URL from a leading-slash path like /api/show. */
export function apiUrl(path: string): string {
  return API_BASE + path
}
