import type { CapacitorConfig } from '@capacitor/cli'

// The packaged app loads the built web assets (dist/) from the device and talks
// to the FastAPI backend on the PC over the LAN at http://10.100.102.123:8000
// (an HTTP, non-TLS origin). Android blocks cleartext HTTP by default, so we
// opt in:
//   - server.cleartext + androidScheme:'http' allow the WebView to make
//     cleartext requests to the LAN backend.
//   - The actual API base URL is baked into the web bundle via VITE_API_BASE
//     (.env.production), so we do NOT point server.url at the backend; we ship
//     the static bundle and let it fetch the LAN API cross-origin (CORS is open
//     on the backend).
const config: CapacitorConfig = {
  appId: 'com.radioai.app',
  appName: 'Radio AI',
  webDir: 'dist',
  server: {
    androidScheme: 'http',
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
  },
}

export default config
