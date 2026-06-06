# KOLAI — Android-Native Rewrite (v2) Design Spec

> **App name:** **KOLAI** (קול + AI — "voice/sound AI").
> **Status:** Design approved in principle (MVP-first, full pivot to native). This spec grounds the implementation plan at `docs/superpowers/plans/2026-06-06-kolai-android-native-rewrite.md`.
> **Date:** 2026-06-06. **Author:** pairing session (Claude).

**Goal:** Rebuild the RadioAI Python engine as a **native Android app that runs standalone on one OnePlus 15** — no PC, no Tailscale, no localhost server. The phone downloads the songs, analyzes them, mixes a continuous DJ-narrated stream from the listener's taste, and plays it endlessly. It still uses the internet for the cloud AI (Gemini, Spotify) — "standalone" means **no PC, not offline**.

**Hard constraints (unchanged):** everything FREE; Hebrew DJ; endless; feels like a real radio.

---

## 1. Key findings that shaped this design (all 2026-verified)

These came out of a research + adversarial-verification pass. They are the reason several pieces differ from a naive "just port it" plan.

### 1.1 The audio source is the dominant risk — not the audio engine
YouTube extraction (NewPipeExtractor / yt-dlp) on-device is **technically doable but increasingly fragile in 2026**:
- YouTube now requires per-video **PO-tokens + SABR-only formats**; yt-dlp needs an external JS runtime and a side PoToken provider that is **flaky on Android/Termux**, and ships fixes roughly **every two weeks** because YouTube keeps breaking it.
- **Google's developer-verification lockdown begins Sept 2026** on certified devices (incl. sideloaded apps). **NewPipe has publicly stated it will not enroll and will stop working on certified devices.** The OnePlus 15 is a certified device → this is a concrete future break, not hypothetical.
- Legal: downloading commercial music from YouTube breaches YouTube ToS and is copyright-sensitive. Enforcement against a single personal user is historically rare → **low-but-nonzero** legal risk; the bigger problem is reliability.

**Implication / decision:** YouTube stays the **primary source for the POC** (it's the only free way to play the specific Hebrew/international songs the listener loves), but it is treated as **best-effort, opt-in, "may break anytime,"** isolated so its failure never stalls the station. A **local-files** source (the user's own music on the phone) is the **reliable fallback** and the only fully-legal, break-proof path. CC catalogs (Jamendo / Free Music Archive) are a possible future discovery source. **See Open Decision OD-1.**

### 1.2 librosa cannot run on Android → analysis must be rewritten
`librosa` pulls `numba`/`llvmlite`, which **do not build/run on Android** (Chaquopy #834; numba aarch64 ABI failures). `pyrubberband` also needs an external CLI binary. So the Python analysis/DSP stack is a hard blocker for "run natively." **Essentia** (C++/NDK) is the correct replacement — it implements the **same Krumhansl-Schmuckler key detection** librosa uses, plus robust beat tracking with a **confidence value** librosa lacks.

### 1.3 The live engine does NOT time-stretch
`time_stretch_to_bpm` (pyrubberband/Rubber Band) is **only** in `mashup.py` (disabled) and the legacy `render_show.py`. The live `BlockRenderer` uses **fixed 1.5 s equal-power crossfades** — no beat-matching stretch. **Therefore the MVP needs no time-stretch library at all**, which removes the GPL Rubber Band licensing problem and the SoundTouch NDK integration from the critical path. The mixer becomes **pure-Kotlin `FloatArray` math**.

### 1.4 ffmpeg-kit is retired (confirmed)
Arthenica ffmpeg-kit was archived Jan–Apr 2025; binaries are gone from Maven/CocoaPods/npm and the old ones SIGBUS-crash on 16 KB-page Android 15+ devices (the OnePlus 15). **There is no MP3 encoder on Android.** → Block output changes from `.mp3` to **`.m4a` (AAC) via the built-in `MediaCodec` + `MediaMuxer`** (decodes everything, plays everywhere, zero dependency). The `.mp3` assumption in `station.py`/`server.py`/the `<audio>` element goes away.

### 1.5 Honest operating costs
- **Spotify "Dev Mode" now requires the app owner to hold Spotify Premium** (effective 2026-03-09) and caps at 5 users. So **live taste sync is not truly free** to operate. Fallback: a **one-time manual taste seed** (export top tracks once → `taste.json`, editable in-app). **See OD-2.**
- **Thermal/battery:** the OnePlus 15 was the worst-throttling SD-8-Elite device tested (~52.7 °C, ~60% sustained). Steady-state a block renders in ~1.5–4.5 min vs a ~9 min budget, so `buffer_ahead=2` is fine **once running** — but **cold start pre-renders ~2 blocks (~a few minutes' wait)** and a hot car needs a **graceful "cooling / degraded" mode** rather than a stall. Expect ~2.5–4.5 h continuous battery.
- **Gemini API keys** embedded in an APK are extractable, and Google **blocks unrestricted keys from 2026-06-19**. → keys are **entered on-device** (not shipped in the bundle), stored in EncryptedSharedPreferences, and each key restricted to the app + a separate Google project (rotation only multiplies quota across separate projects).

---

## 2. Architecture: Python module → Android replacement

The engine logic is preserved; only the *substrate* changes. Orchestration/IO/network → **Kotlin**; heavy DSP/MIR → **C++/NDK**; cloud calls → **Kotlin HTTP**.

| Python module (today) | Responsibility | Android replacement | Lang |
|---|---|---|---|
| `fetcher.py` (yt-dlp) | search by "artist title", pick best, download audio | **NewPipeExtractor** (search + audio stream) + OkHttp download; candidate-scoring ported 1:1; **+ LocalFiles source** | Kotlin |
| `analyzer.py` (librosa) | BPM, beat times, key→Camelot, energy, onsets | **Essentia** (RhythmExtractor2013 + KeyExtractor + RMS + OnsetRate) via JNI; **+ beat-confidence & octave-fold guards** | C++/NDK + Kotlin |
| `keys.py` | Camelot math | port as-is (pure data tables) | Kotlin |
| `scoring.py`, `mixplanner.py` | compatibility, transition choice | port as-is | Kotlin |
| `mixrenderer.py` (numpy/scipy) | crossfade, duck, band-split, (stretch), normalize | **pure-Kotlin `FloatArray`** (crossfade/duck/normalize); band-split via **iirj** (deferred); **no time-stretch in MVP** | Kotlin |
| `decode/encode` (librosa.load / ffmpeg / soundfile) | decode any container → PCM; encode block | **MediaExtractor+MediaCodec** (decode) / **MediaCodec AAC + MediaMuxer** (encode `.m4a`) | Kotlin |
| `djbrain.py` (google-genai) | LLM writes Hebrew DJ lines / banter | **Gemini REST** (Ktor/OkHttp) + key rotation; all prompt/scrub/parse logic ported verbatim | Kotlin |
| `voice.py` (google-genai TTS) | TTS → WAV, two-voice stitch | **Gemini REST (AUDIO modality)** + `pcm_to_wav` ported (44-byte WAV header) | Kotlin |
| `taste.py` (spotipy) | read Spotify top tracks/artists | **OAuth PKCE (no secret)** via `spotify/android-auth` + 2 Web API GETs; **+ manual-seed fallback** | Kotlin |
| `setlist.py`, `planner_rolling.py`, `moods.py`, `djcontext.py` | setlist gen/refine, rolling taste refresh, moods, clock/weather/news | port as-is (Kotlin + REST); moods/context **deferred** post-MVP | Kotlin |
| `block_renderer.py` | assemble one block (cadence + audio) | port the cadence state machine 1:1 | Kotlin |
| `station.py` (StationEngine) | render-ahead, buffer, prune, reset | **Kotlin coroutine supervisor** inside the media service | Kotlin |
| `server.py` (FastAPI + range) | HTTP endpoints, serve blocks | **deleted** — blocks are local files | — |
| `useStation.ts` + `<audio>` | playback, advance, prefetch | **Media3 ExoPlayer + MediaSessionService** (gapless local playlist, lock-screen/car) | Kotlin |
| Capacitor web wrapper | app shell | **native Kotlin + Jetpack Compose** | Kotlin |
| `stems.py`, `mashup.py` (Demucs/PyTorch) | stem split / mashups | **omit** (no practical on-device PyTorch); already disabled | — |

---

## 3. MVP scope (v1) vs deferred

**MVP — "endless personal radio, on the phone":**
1. Taste in (Spotify PKCE → `taste.json`, with manual-seed fallback).
2. LLM song picks (Gemini REST) — `setlist` + rolling planner + the existing **familiar/discovery (~1-in-4)** rule, ported.
3. Acquire audio (NewPipeExtractor primary, **LocalFiles fallback**) → cache.
4. Decode (MediaCodec) → mono `FloatArray` @ 44.1 k.
5. Analyze (Essentia NDK): BPM/beats/key/energy **+ confidence**, with octave-fold + low-confidence fallback to plain crossfade.
6. Mix (pure-Kotlin): equal-power crossfade + duck under DJ. **No time-stretch, no bass-swap.**
7. DJ voice: Gemini LLM line + Gemini TTS → WAV, **single male voice (Algieba)**, ducked; the natural-cadence `_plan()` (talk-sometimes, skip-if-nothing-cool, deeper-when-quiet) ported as-is.
8. Encode block → `.m4a`.
9. Station engine: coroutine render-ahead (`buffer_ahead=2`), prune behind, **reset-on-refresh** (reshuffle).
10. Playback: Media3 ExoPlayer + MediaSessionService — gapless, lock-screen + Android Auto, background.
11. Keys entered on-device; EncryptedSharedPreferences.
12. Thermal-aware render loop (idle when buffer full; cooling/degraded mode).
13. UI: minimal Compose "now playing" + play/pause (the heavy React UI is not ported in MVP).

**Deferred (post-MVP), each already designed/proven in Python:**
- Mood modes (late night / party / focus / morning) — `moods.py`.
- Two-host banter (female co-host **Aoede**) — needs the two-voice TTS stitch.
- Context beats: weather / news / topic — `djcontext.py` (+ free APIs).
- Settings UI (DJ talkativeness).
- Beat-match time-stretch (SoundTouch) + bass-swap crossfade (iirj).
- CC-catalog discovery sources (Jamendo/FMA).
- Richer Compose UI (palette, animations) matching the current web look.

---

## 4. Tech stack & licenses (all free)

| Concern | Choice | License | 2026 status |
|---|---|---|---|
| Language / UI | Kotlin 2.3 + Jetpack Compose | Apache-2.0 | current |
| Build | AGP 9.x + Gradle 9, version catalog, multi-module | Apache-2.0 | current |
| Native | NDK r28+ / CMake / C++17 (**16 KB-aligned `.so`**) | — | required for OnePlus 15 |
| Audio acquire | NewPipeExtractor (+ WebView PoToken) | **GPLv3** | maintained ~2026-03 |
| Decode/encode | MediaExtractor/MediaCodec/MediaMuxer | Apache-2.0 (platform) | first-party |
| MIR / analysis | Essentia (NDK, bundled FFT) | **AGPLv3** (free non-commercial) | rolling 2.1-beta6-dev |
| Mixer | own Kotlin `FloatArray` DSP | — | n/a |
| Playback | AndroidX Media3 (ExoPlayer + session) | Apache-2.0 | 1.10.1 (May 2026) |
| Gemini LLM+TTS | raw REST via Ktor + kotlinx.serialization | Apache-2.0 | current |
| Spotify auth | `spotify/android-auth` v4.0.1 (PKCE) | Apache-2.0 | maintained ~2026-03 |
| Secure storage | AndroidX Security (EncryptedSharedPreferences) | Apache-2.0 | current |
| Tests | JUnit5 + Robolectric 4.16 + androidx.test + googletest (native) | EPL/Apache | current |

**License note:** NewPipeExtractor (GPLv3) + Essentia (AGPLv3) make the app **copyleft / source-available**. That is **fine for a personal, non-distributed app** but blocks any closed-source release. Keep `:acquire` and `:analyze` isolable behind interfaces so either can be swapped if distribution is ever wanted.

---

## 5. Module layout (Gradle multi-module)

```
kolai/
  settings.gradle.kts        # :app :core :acquire :analyze :mix :voice :station :dsp
  gradle/libs.versions.toml
  :core      — models (TrackAnalysis, Song, Block…), Camelot/keys, config, key store
  :acquire   — AudioSource iface; NewPipeSource, LocalFilesSource; candidate scoring; cache
  :analyze   — AudioDecoder (MediaCodec→PCM); Analyzer (JNI→Essentia); octave/confidence guards
  :dsp       — C++/CMake: libessentia.so + JNI bridge (analyze only for MVP)
  :mix       — FloatArray DSP (crossfade/duck/normalize/start-on-beat); BlockEncoder (→.m4a)
  :voice     — GeminiTextClient, GeminiTtsSynth (REST), VoiceRenderer, pcm_to_wav
  :station   — SetlistPlanner, RollingPlanner, DJBrain, BlockRenderer, StationEngine (coroutine)
  :app       — Compose UI, KolaiMediaService (MediaSessionService), MediaController, onboarding/keys
```

DSP/JNI rule: pass **whole buffers** across the JNI boundary (direct `ByteBuffer`/`FloatArray`), never per-sample callbacks.

---

## 6. Cross-cutting design

**Render-ahead loop (StationEngine port).** A supervisor coroutine on the media service: renders blocks ahead of `current` on `Dispatchers.Default` (DSP) / `Dispatchers.IO` (network), appends finished `.m4a` files to the ExoPlayer playlist, **suspends when `buffer_ahead` is satisfied** (mirrors `_wake`), prunes `< current - keep_behind` (delete file + `removeMediaItem` by `mediaId`). `onMediaItemTransition` is the `advance(n)` signal. `reset()` = `clearMediaItems()` + bump generation + wipe blocks dir + reseed planner (the shuffle-on-refresh behavior).

**Cold start & thermal.** First launch shows "tuning in" while ~2 blocks pre-render (honest few-minute wait). The loop reads thermal state (`PowerManager.getCurrentThermalStatus`); under throttle it caps worker threads and can downgrade analysis to lighter features, surfacing a "cooling down" state instead of stalling.

**Keys & security (tiered).**
- *This POC (single self-installed APK):* no keys in the bundle — first-run screen where the user pastes their Gemini key(s) and Spotify client_id; stored encrypted. Risk: low.
- *Spotify:* PKCE, **no client secret** (fix the current `taste.py` which wrongly uses one); client_id is public.
- *Gemini:* restrict each key to the app (package + SHA-1) and to the Generative Language API; one key per Google project for real rotation.
- *Wider distribution (not now):* a free Cloudflare/Vercel proxy holding the key server-side — explicitly deferred.

**Beat-tracking guards (from verification).** Essentia emits a beat confidence; treat `<1.5` as uncertain → skip any tempo logic, use a plain equal-power crossfade. Octave-normalize BPM before compatibility scoring (fold 0.5×/2× into a 60–180 canonical range) so half/double-time detection can't wrongly block a good blend. (Time-stretch is off in MVP, so the 2× stretch artifact can't occur anyway.)

---

## 7. Risks & open decisions

**Risks (carried into the plan):**
- R1 — **YouTube extraction breakage / Sept-2026 verification lockdown.** Mitigate: isolate behind `AudioSource`; LocalFiles fallback; cache aggressively; per-song failure skips, never stalls the buffer.
- R2 — **Essentia NDK build** is the hardest single task (no turnkey AAR; arm64 build quirks). Front-load it; pin a known-good commit; golden-file tests vs current librosa output.
- R3 — **Thermal throttle/battery** in a hot car. Mitigate: idle loop, thermal-aware degrade, cooling state.
- R4 — **Cold-start latency** (~minutes). Mitigate: clear "tuning" UX; consider rendering a shorter first block.
- R5 — **Spotify Premium requirement** for live taste. Mitigate: manual-seed fallback mode.
- R6 — **Copyleft (GPL/AGPL)** blocks closed-source distribution. Accepted for personal use; keep modules swappable.

**Decisions (made 2026-06-06):**
- **OD-1 — Primary audio source → (a) YouTube primary + LocalFiles fallback.** Preserves the "play the songs I love" vision; YouTube isolated behind `AudioSource` as best-effort/opt-in with the verified caveats (R1), LocalFiles is the reliable fallback. Both built in Phase 2.
- **OD-2 — Spotify taste → (a) Live PKCE sync.** Always-fresh taste; **requires the owner to hold Spotify Premium** (Spotify Dev-Mode rule, 2026-03-09) — accepted. The manual-seed code path is still built as a no-Premium fallback but live PKCE is the primary flow (Phase 1.4).

---

## 8. Testing strategy
- **Kotlin engine logic** (scoring, planner, cadence, candidate-scoring, WAV header, Camelot): JUnit5, pure-JVM, fast.
- **Android-framework bits** (key store, MediaController state): Robolectric.
- **Native DSP/MIR**: googletest in CMake **+ golden-file comparison** of Essentia output vs the current librosa `TrackAnalysis` on a small Hebrew/pop reference set (assert agreement after octave-folding, not bit-identity).
- **Real audio path** (decode→analyze→mix→encode→play): instrumented `connectedAndroidTest` on the OnePlus 15.
- All free: `./gradlew assembleDebug connectedAndroidTest`; GitHub Actions free tier optional.
```
