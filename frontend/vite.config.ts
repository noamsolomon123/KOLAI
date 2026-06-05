import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: proxy /api to the FastAPI backend.
// We target 127.0.0.1 (IPv4) explicitly rather than "localhost": on Windows,
// "localhost" can resolve to IPv6 ::1 first, which may be occupied by other
// services (Docker/WSL relays), causing spurious 404s. uvicorn dev typically
// binds 127.0.0.1.
// Prod: the built app is served BY FastAPI at the same origin, so all fetches
// use RELATIVE /api/* URLs and need no proxy.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8800',
        changeOrigin: true,
      },
    },
  },
})
