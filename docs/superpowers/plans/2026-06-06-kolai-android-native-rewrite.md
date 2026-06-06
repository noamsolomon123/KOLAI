# KOLAI — Android-Native Rewrite (MVP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
>
> **Companion spec:** `docs/superpowers/specs/2026-06-06-kolai-android-native-rewrite-design.md` — read it first. It explains *why* each choice was made (the 2026-verified findings).

**Goal:** Ship **KOLAI** — a native Android app that runs the endless personal-radio engine **standalone on one OnePlus 15** (no PC), delivering taste-picked songs with a Hebrew DJ, mixed and narrated on-device.

**Architecture:** Kotlin (orchestration/IO/cloud) + a single C++/NDK module for MIR analysis (Essentia). Blocks are rendered ahead by a coroutine engine inside a Media3 `MediaSessionService` and played gaplessly from local `.m4a` files. No backend, no localhost server.

**Tech stack:** Kotlin 2.3, Jetpack Compose, AGP 9 / Gradle 9, NDK r28+ (16 KB-aligned), AndroidX Media3 1.10, NewPipeExtractor, Essentia (NDK), MediaCodec/MediaMuxer, Ktor + kotlinx.serialization (Gemini REST), `spotify/android-auth` (PKCE), EncryptedSharedPreferences. JUnit5 / Robolectric / googletest.

**Ground rules:** DRY, YAGNI, TDD where logic is pure, frequent commits. Single ABI `arm64-v8a`. minSdk 31 / compile+target 37. Port pure logic 1:1 from the Python modules (the algorithms are already proven). Keep `:acquire` and `:analyze` behind interfaces so the GPL/AGPL pieces stay swappable.

**Build target & repo:** new Gradle project at `C:\dev\RadioAI\android\` (the Python `backend/` stays untouched as reference; the Capacitor `frontend/` is retired but not deleted until the MVP plays end-to-end).

---

## Phase 0 — Skeleton + de-risking spikes

> Prove the three things that can sink the project **before** building on them. If a spike fails, we replan that subsystem (e.g. Essentia → aubio+hand-rolled chroma; NewPipe → LocalFiles-only) rather than discovering it late.

### Task 0.1: Gradle multi-module skeleton that installs on the device

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create stub library modules: `:core :acquire :analyze :mix :voice :station` (`com.android.library`) and `:dsp` (NDK/CMake, empty `libkolaidsp` for now)
- Create: `android/app/src/main/java/ai/kolai/app/MainActivity.kt` (Compose "KOLAI" hello screen)

- [ ] **Step 1:** Scaffold the modules, version catalog, Compose BOM 2026.05, minSdk 31 / compile+target 37, NDK 28, `externalNativeBuild { cmake }` in `:dsp`.
- [ ] **Step 2:** Compose screen renders the word "KOLAI".
- [ ] **Step 3:** Verify — `./gradlew :app:assembleDebug` succeeds; `adb install` on the OnePlus 15 launches and shows the screen.
- [ ] **Step 4:** Commit — `chore(android): KOLAI multi-module skeleton builds + installs`.

### Task 0.2: Media3 plays a local `.m4a` gaplessly (playback proof)

**Files:** `android/app/.../KolaiMediaServiceSpike.kt`; a bundled test `assets/block_0.m4a`, `block_1.m4a`.

- [ ] **Step 1:** Minimal `MediaSessionService` + ExoPlayer with a 2-item local playlist; `foregroundServiceType="mediaPlayback"` + permissions in the manifest.
- [ ] **Step 2:** Verify on device — plays block 0 → 1 back-to-back, lock-screen controls appear, audio survives screen-off. Note any audible seam (informs encode settings later).
- [ ] **Step 3:** Commit — `feat(android): Media3 foreground service gapless local playback spike`.

### Task 0.3: **Essentia NDK build** spike (highest risk — R2)

**Files:** `android/dsp/CMakeLists.txt`, `android/dsp/src/essentia_jni.cpp`, `android/analyze/.../EssentiaSpike.kt`, `android/dsp/src/main/jniLibs/arm64-v8a/libessentia.so` (built).

- [ ] **Step 1:** Cross-compile Essentia for `arm64-v8a` with NDK r28 (`build_android.sh`, bundled KissFFT to avoid FFTW/GPL hassle, 16 KB-aligned). Pin the exact commit SHA in a `dsp/ESSENTIA_COMMIT.txt`.
- [ ] **Step 2:** ~80-line JNI bridge: `analyzePcm(float[] pcm, int sr) -> {bpm, beatTimes[], keyTonic, keyScale, energy, beatConfidence}` calling `RhythmExtractor2013(multifeature)` + `KeyExtractor` + RMS.
- [ ] **Step 3:** Verify on device — feed a bundled 30 s WAV, log plausible BPM/key/confidence. Compare to the Python `analyzer.analyze()` output for the same file (rough agreement after octave-fold).
- [ ] **Step 4:** Commit — `feat(dsp): Essentia arm64 .so + JNI analyze bridge spike`.
- [ ] **Fallback if Step 1 is infeasible in ~3 days:** switch `:analyze` to **aubio (NDK) for BPM/onset + pure-numpy-equivalent chroma + Krumhansl in Kotlin**; update the spec's §1.2 and this phase, then continue.

### Task 0.4: On-device YouTube extraction spike (risk R1)

**Files:** `android/acquire/.../NewPipeSpike.kt`, WebView-backed `PoTokenProvider`.

- [ ] **Step 1:** `NewPipe.init` with an OkHttp downloader; search `"<artist> <title>"`; resolve highest-bitrate audio stream; download to cache; register a WebView PoTokenProvider.
- [ ] **Step 2:** Verify on **real cellular** (not just wifi): a known song downloads and decodes. Try ~10 songs to gauge bot-flagging.
- [ ] **Step 3:** Document observed reliability in `docs/` (informs OD-1).
- [ ] **Step 4:** Commit — `feat(acquire): NewPipeExtractor on-device download + PoToken spike`.

### Task 0.5: Gemini REST (text + TTS) spike

**Files:** `android/voice/.../GeminiSpike.kt`.

- [ ] **Step 1:** Ktor POST to `:generateContent` for text; and for TTS with `responseModalities=["AUDIO"]` + `prebuiltVoiceConfig.voiceName="Algieba"`; base64-decode PCM, wrap to WAV, play it.
- [ ] **Step 2:** Verify a Hebrew line synthesizes and plays in the male voice; 429 rotates to the next key.
- [ ] **Step 3:** Commit — `feat(voice): Gemini REST text+TTS spike`.

**Phase 0 gate:** all five spikes green (or fallbacks chosen). Only then proceed.

---

## Phase 1 — Core + cloud clients

### Task 1.1: `:core` models + Camelot (pure port, TDD)
**Files:** `core/.../models.kt` (`Song`, `TrackAnalysis`, `DJSlot`, `BlockMeta`…), `core/.../Keys.kt`; tests `core/src/test/.../KeysTest.kt`.
- [ ] Port `keys.py` (`camelot_from_key`, `camelot_relation`) and `models.py`. Test first: known key→Camelot pairs and relations (`same`/`relative`/neighbour) mirrored from the Python.
- [ ] `./gradlew :core:test`; commit `feat(core): models + Camelot port`.

### Task 1.2: On-device key store
**Files:** `core/.../SecureKeys.kt` (EncryptedSharedPreferences: gemini keys list, spotify client_id), Robolectric test.
- [ ] Store/retrieve/clear; commit `feat(core): encrypted key store`.

### Task 1.3: Gemini clients (port `djbrain` + `voice` REST)
**Files:** `voice/.../GeminiTextClient.kt`, `voice/.../GeminiTtsSynth.kt`, `voice/.../Wav.kt` (`pcmToWav`), tests.
- [ ] TDD `pcmToWav` (exact 44-byte header, 24 k mono 16-bit) and the key-rotation loop (mock 429 → advance). Port the `_clean`/`_finish` Hebrew scrubbing + `_extract_json_array` from `djbrain.py` with their unit tests.
- [ ] Commit `feat(voice): Gemini text+TTS REST clients with rotation`.

### Task 1.4: Spotify taste via PKCE (port `taste.py`, fix the secret)
**Files:** `core/.../taste/SpotifyAuth.kt` (PKCE, `spotify/android-auth`), `TasteRepository.kt`, `parseProfile` + `taste.json` cache; **manual-seed** path; tests for parsing + cache.
- [ ] TDD `parseProfile` (map `items[].name/artists[0].name/duration_ms` → `TasteProfile`) and JSON cache round-trip against the existing `taste.json` contract. Wire PKCE login + the two GETs (`/me/top/tracks`, `/me/top/artists`). Add `seedFromManual(...)`.
- [ ] Commit `feat(core): Spotify PKCE taste + manual-seed fallback`.

---

## Phase 2 — Acquire + decode

### Task 2.1: `AudioSource` interface + candidate scoring (port `fetcher._candidate_score`)
**Files:** `acquire/.../AudioSource.kt`, `acquire/.../Scoring.kt`, tests.
- [ ] TDD the scoring port: duration-delta, good/bad keyword lists, Hebrew-title boost, `log10(views)` popularity, `pick_best_candidate`. Mirror `fetcher.py` cases exactly.
- [ ] Commit `feat(acquire): AudioSource iface + candidate scoring port`.

### Task 2.2: `NewPipeSource` + `LocalFilesSource` + cache
**Files:** `acquire/.../NewPipeSource.kt` (from the 0.4 spike, productionized: failure→skip), `acquire/.../LocalFilesSource.kt` (scan MediaStore, fuzzy match title/artist), `acquire/.../Cache.kt` (sha1 key, same as Python).
- [ ] Both implement `AudioSource.fetch(song): File?`. A failed fetch returns null (never throws into the engine). Robolectric/instrumented test for cache hit/miss.
- [ ] Commit `feat(acquire): NewPipe + LocalFiles sources with cache`.

### Task 2.3: `AudioDecoder` (MediaCodec → mono `FloatArray` @ 44.1 k)
**Files:** `analyze/.../AudioDecoder.kt`, instrumented test.
- [ ] `decodeToPcm(file): FloatArray` via MediaExtractor+MediaCodec; batch ~0.5 MB inputs; trim priming; resample to 44.1 k mono in Kotlin. Test: decode a bundled `.m4a`/`.webm`, assert sane length/SR.
- [ ] Commit `feat(analyze): MediaCodec decode to PCM`.

---

## Phase 3 — Analyze (Essentia)

### Task 3.1: `Analyzer` over the JNI bridge + guards
**Files:** `analyze/.../Analyzer.kt` (wraps `:dsp`), `analyze/.../BeatGuards.kt`, tests.
- [ ] `analyze(pcm,sr): TrackAnalysis`. Add **octave-fold** (fold 0.5×/2× BPM into 60–180) and **confidence gate** (`<1.5` → mark beats unreliable so the mixer falls back to plain crossfade). Map tonic/scale → Camelot via `:core`. TDD the guards with synthetic inputs.
- [ ] Commit `feat(analyze): Analyzer + octave/confidence guards`.

### Task 3.2: Golden-file MIR validation (R2 safety net)
**Files:** `analyze/src/androidTest/.../GoldenAnalysisTest.kt`, `androidTest/assets/refs/*.json` (current librosa output for ~6 Hebrew/pop tracks), bundled audio.
- [ ] Generate refs from the Python `analyzer.analyze()` (one-off script). Assert KOLAI agrees on key (Camelot) and BPM **after octave-folding** within tolerance.
- [ ] Commit `test(analyze): golden-file MIR vs librosa`.

---

## Phase 4 — Mix + encode

### Task 4.1: Pure-Kotlin mixer (port `mixrenderer` essentials, TDD)
**Files:** `mix/.../Mixer.kt` (`equalPowerCrossfade`, `duck`, `trimSilence`, `startOnBeat`, `snapOverlapToBeats`, `peakNormalizeClip`), tests.
- [ ] TDD each as `FloatArray` math, 1:1 with the numpy versions (cosine equal-power ramps, dB-ramp duck + overlay, beat-trim). No time-stretch, no band-split in MVP.
- [ ] Commit `feat(mix): pure-Kotlin crossfade/duck/normalize port`.

### Task 4.2: `BlockEncoder` → `.m4a`
**Files:** `mix/.../BlockEncoder.kt` (MediaCodec AAC + MediaMuxer), instrumented test.
- [ ] `encode(pcm,sr): File` writing `block_{n}.m4a`. Test: encode→decode round-trip ≈ input length.
- [ ] Commit `feat(mix): AAC/m4a block encoder`.

---

## Phase 5 — Station orchestration (Kotlin ports)

### Task 5.1: `SetlistPlanner` + `RollingPlanner` (port `setlist.py` + `planner_rolling.py`)
**Files:** `station/.../SetlistPlanner.kt`, `RollingPlanner.kt`, tests with a fake LLM.
- [ ] Port the generate→critique→refine 2-pass, the craft rules incl. the **familiar+discovery (~1-in-4)** rule, `parse_setlist` dedup (exact + base-title), no-repeat window, taste refresh. TDD parsing/dedup with canned LLM JSON.
- [ ] Commit `feat(station): setlist + rolling planner port`.

### Task 5.2: `DJBrain` (port the prompts + cadence helpers)
**Files:** `station/.../DJBrain.kt`, tests.
- [ ] Port `write_break` (song/weather/news/topic beats — weather/news/topic kept but only "song" exercised in MVP), `allow_skip`→null, `words_for_seconds`, depth/wit/naming lines, `_finish` budget cap. (Banter deferred.) TDD skip detection + budget capping.
- [ ] Commit `feat(station): DJBrain port`.

### Task 5.3: `BlockRenderer` cadence + assembly (port `block_renderer.py`)
**Files:** `station/.../BlockRenderer.kt`, tests with fakes (no net/audio/LLM).
- [ ] Port `_plan()` exactly: opening back-announce, per-boundary `forced/eligible/want_banter`, cross-block `songs_since_talk`/`talk_count`. Assemble via `:acquire`→`:analyze`→`:mix`: load+analyze (skip failures), duck opening over song 0, talkover+crossfade per boundary, build segments/meta, encode. **Banter branch stubbed** (single-voice only in MVP).
- [ ] TDD the plan/cadence with a seeded RNG mirroring the Python test.
- [ ] Commit `feat(station): BlockRenderer cadence + assembly port`.

### Task 5.4: `StationEngine` coroutine (port `station.py`)
**Files:** `station/.../StationEngine.kt`, tests.
- [ ] Port the sliding-window engine to coroutines: `ensureThrough`, generation counter, `buffer_ahead=2`/`keep_behind=2`, `advance`, `prune` (delete file), `reset` (reshuffle, bump generation, wipe dir), idle/`wake` via a `Channel`/`Mutex`. TDD with a fake renderer (assert render-ahead + prune + reset semantics, incl. the generation-discards-stale-render rule).
- [ ] Commit `feat(station): StationEngine coroutine port`.

---

## Phase 6 — Playback service wiring

### Task 6.1: `KolaiMediaService` — engine ↔ ExoPlayer
**Files:** `app/.../KolaiMediaService.kt` (productionize 0.2), tests where possible.
- [ ] Render-ahead loop appends finished blocks via `addMediaItem(mediaId=blockIndex)`; `onMediaItemTransition` → `engine.advance(n)`; prune → `removeMediaItem` by `mediaId` (offset-safe); `reset()` → `clearMediaItems()`. Persist `{currentIndex, generation}` for process-death restore.
- [ ] Commit `feat(app): media service drives the station engine`.

### Task 6.2: Thermal-aware + idle backpressure (R3)
**Files:** `station/.../ThermalGovernor.kt`, wire into the loop.
- [ ] Suspend the loop when `buffer_ahead` satisfied; read `PowerManager.getCurrentThermalStatus`; under throttle cap worker threads / degrade analysis and emit a `Cooling` state. Test the governor logic (fake thermal levels).
- [ ] Commit `feat(station): thermal-aware idle render loop`.

---

## Phase 7 — UI, onboarding, end-to-end

### Task 7.1: Onboarding / key entry
**Files:** `app/.../OnboardingScreen.kt` (paste Gemini keys + Spotify client_id → `SecureKeys`; Spotify PKCE login; or manual taste seed).
- [ ] First-run gate; commit `feat(app): onboarding + key entry`.

### Task 7.2: Now-playing Compose UI
**Files:** `app/.../NowPlayingScreen.kt` bound to a `MediaController`.
- [ ] Minimal: station name, current title/artist (from block meta), play/pause, `Tuning`/`Cooling`/`Ready`/`Error` states. (Rich visuals deferred.) Commit `feat(app): now-playing UI`.

### Task 7.3: End-to-end on the OnePlus 15
- [ ] Cold start → tunes in → plays taste-picked songs with the Hebrew DJ, endlessly, in the car over cellular; lock-screen/Android Auto controls work; refresh reshuffles; a failed download skips without stalling; hot-car cooling mode degrades gracefully.
- [ ] Capture a short demo; commit `chore: KOLAI MVP end-to-end verified on device`.
- [ ] Retire `frontend/` (Capacitor) once green; note the Python `backend/` is kept as reference only.

---

## Execution order & checkpoints
Phase 0 is a **hard gate** (de-risk first). Phases 1–4 are largely independent ports and can be parallelized across subagents; Phase 5 depends on 1–4; Phases 6–7 depend on 5. Commit per task. After each phase, run that module's tests green before moving on.

## Self-review notes
- Spec coverage: every spec §2 module maps to a task; deferred items (moods, banter, context beats, settings, time-stretch, bass-swap, CC sources, rich UI) are explicitly out of MVP.
- The two **open decisions (OD-1 audio source, OD-2 Spotify taste)** must be answered before Phase 2.1 / Phase 1.4 respectively — they change which `AudioSource`/taste path is primary.
- Biggest risk (Essentia build) is isolated in Task 0.3 with a defined fallback, so it can't silently block the whole project.
