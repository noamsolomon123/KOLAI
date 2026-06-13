# Essentia (arm64-v8a) build recipe for `:dsp`

On-device music analysis (BPM/beat-times, key, energy) for KOLAI. Essentia
cannot be `apt`-installed for Android, so we cross-compile a static
`libessentia.a` for `arm64-v8a` and link it into the JNI bridge
(`kolaidsp.cpp`). librosa (Python) is not usable on-device; this replaces it.

## What ships in the module (committed prebuilt)
- `src/main/cpp/essentia/lib/arm64-v8a/libessentia.a`  (~37 MB, AArch64 ELF64)
- `src/main/cpp/essentia/include/essentia/`             (public headers)
- `src/main/cpp/essentia/include/eigen3/{Eigen,unsupported}/` (Eigen 3.4.0 headers; `types.h` includes `<unsupported/Eigen/CXX11/Tensor>`)

## Build environment
- Built inside WSL2 Ubuntu 24.04 (the waf build is Linux-oriented).
- A **Linux** NDK is required (the Windows NDK toolchains are windows-x86_64).
  Used `android-ndk-r27c` (Linux). The OUTPUT is arm64-only target code, so a
  Linux host build is fine; it runs on-device and is packaged by the Windows
  Gradle build.
- No sudo / no apt needed. Dependencies avoided via `--lightweight=` and KissFFT.
  Eigen is header-only and injected via a small wscript patch.

## Pinned source
- Repo: https://github.com/MTG/essentia
- Commit: `b9fa6cb674ca43dfb94d28d293aeda441c6745db` (master, 2026-05-20; 2.1-beta6-dev line)
- Eigen: 3.4.0 (gitlab.com/libeigen/eigen archive)

## Gotchas discovered (important)
1. The NDK zip was extracted with Python `zipfile`, which does NOT preserve
   symlinks -> `bin/clang` became an 8-byte text file ("clang-18"), breaking the
   toolchain (compiler hangs / SHLVL fork-storm). Fix: recreate the 35 symlink
   entries from the zip (read S_ISLNK entries, `os.symlink`). Prefer `unzip`.
2. Do NOT symlink the NDK `aarch64-linux-android31-clang++` wrapper into a shim
   dir and name it `clang++`: the wrapper does `dirname "$0"` to find sibling
   binaries, so a shim symlink makes it call itself -> infinite `--target=...`
   recursion. Use a shim that `exec`s the real NDK binary with an ABSOLUTE path.

## Exact recipe (reproducible)
```bash
# 0) tools: git, python3, wget, tar (no sudo). NDK r27c (Linux), Eigen 3.4.0.
cd ~/essentia-build
wget https://dl.google.com/android/repository/android-ndk-r27c-linux.zip -O ndk.zip
python3 -c "import zipfile; zipfile.ZipFile('ndk.zip').extractall('.')"
# fix symlinks zipfile dropped:
python3 - <<'PY'
import zipfile, os, stat
z=zipfile.ZipFile('ndk.zip')
for zi in z.infolist():
    if stat.S_ISLNK(zi.external_attr>>16):
        t=z.read(zi.filename).decode().strip()
        if os.path.lexists(zi.filename): os.remove(zi.filename)
        os.symlink(t, zi.filename)
PY
wget https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz -O eigen.tar.gz && tar xzf eigen.tar.gz

# 1) clang/clang++ shim that targets API 31 arm64 (absolute path, no recursion)
TC=$PWD/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin
mkdir -p shim
printf '#!/bin/bash\nexec "%s/clang"   --target=aarch64-linux-android31 "$@"\n' "$TC" > shim/clang
printf '#!/bin/bash\nexec "%s/clang++" --target=aarch64-linux-android31 "$@"\n' "$TC" > shim/clang++
ln -sf "$TC/llvm-ar" shim/ar; ln -sf "$TC/llvm-ranlib" shim/ranlib; ln -sf "$TC/llvm-strip" shim/strip
chmod +x shim/clang shim/clang++

# 2) clone + pin essentia
git clone https://github.com/MTG/essentia.git
cd essentia && git checkout b9fa6cb674ca43dfb94d28d293aeda441c6745db

# 3) PATCH src/wscript: the eigen3 check uses pkg-config (absent). Replace the
#    `ctx.check_cfg(package=lib_map['EIGEN3'], ...)` call with an env-driven
#    include path (see 03_patch_wscript.sh): if EIGEN3_INCLUDE is set, set
#    ctx.env.INCLUDES_EIGEN3=[that] and mark HAVE_EIGEN3.

# 4) configure + build (static, KissFFT, no external deps, ignore LPC)
export PATH=$PWD/../shim:$PATH CC=clang CXX=clang++ AR=ar RANLIB=ranlib STRIP=strip
export EIGEN3_INCLUDE=$PWD/../eigen-3.4.0
python3 ./waf configure --cross-compile-android --lightweight= --fft=KISS \
        --ignore-algos=LPC --build-static --prefix=$PWD/../install
python3 ./waf -j$(nproc)
python3 ./waf install   # -> ../install/lib/libessentia.a  +  ../install/include/essentia/

# 5) copy into module
DST=/mnt/c/dev/RadioAI/android/dsp/src/main/cpp/essentia
mkdir -p $DST/lib/arm64-v8a $DST/include/essentia $DST/include/eigen3
cp ../install/lib/libessentia.a $DST/lib/arm64-v8a/
cp -r ../install/include/essentia/. $DST/include/essentia/
cp -r ../eigen-3.4.0/Eigen ../eigen-3.4.0/unsupported $DST/include/eigen3/
```

## Algorithms retained (verified not in the ignore list)
RhythmExtractor2013 (+ its multifeature beat trackers), KeyExtractor, Key,
RMS, FFTK/IFFTK (KissFFT). Ignored: audio loaders (ffmpeg/samplerate), Yaml,
TagLib, Tensorflow, Gaia, Chromaprint, LPC. We feed PCM directly from Kotlin,
so the loaders are not needed.

## JNI contract
`Java_ai_kolai_dsp_KolaiDsp_analyzePcmJson(FloatArray pcm, int sr) -> String`
returns JSON: `{bpm, beatConfidence, energy, keyTonic, keyScale, keyStrength,
sampleRate, beatTimes[]}` (or `{"error":...}`).
---

## x86_64-android build (for the standard Android emulator)

Added so the app runs on a **standard x86_64 Android emulator** for unattended
behavior/soak tests on the PC (the device-only `arm64-v8a` build crashes on the
emulator when `System.loadLibrary` finds no matching ABI). The APK now ships
**both** ABIs; each links its own `essentia/lib/${ANDROID_ABI}/libessentia.a`.

### Key finding
`./waf configure --cross-compile-android` does **NOT** set any `-march`/arch
flags (see `wscript`: its Android block only `find_program`s clang/clang++, adds
`-std=c++11`, and `-Wl,-soname,libessentia.so -latomic`). The target ABI is
determined **entirely by the clang shim's `--target=`**. So the x86_64 build is
the *exact same waf invocation* as arm64 — only the shim's `--target` changes
from `aarch64-linux-android31` to `x86_64-linux-android31`. The `-msse*` block in
`wscript` is explicitly skipped under `--cross-compile-android`, and the
x86_64-linux-android target enables SSE by default, so no x86 FPU flags needed.

### Deltas vs the arm64 recipe (everything else identical)
- Worked in `~/essentia-build-x86` (NDK r27c + eigen-3.4.0 reused from the arm64
  tree via symlink; fresh local `git clone` of the pinned essentia; same wscript
  Eigen patch).
- Shim `--target=x86_64-linux-android31` (NOT aarch64). Same absolute-path
  `exec` shim to avoid wrapper self-recursion.

```bash
# (NDK r27c Linux + eigen-3.4.0 already present from the arm64 build; reuse them)
BASE=~/essentia-build-x86
mkdir -p "$BASE" && cd "$BASE"
ln -s ~/essentia-build/android-ndk-r27c android-ndk-r27c
ln -s ~/essentia-build/eigen-3.4.0      eigen-3.4.0

# 1) x86_64 clang/clang++ shim (absolute exec, no self-recursion)
TC=$BASE/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin
mkdir -p shim
printf '#!/bin/bash\nexec "%s/clang"   --target=x86_64-linux-android31 "$@"\n' "$TC" > shim/clang
printf '#!/bin/bash\nexec "%s/clang++" --target=x86_64-linux-android31 "$@"\n' "$TC" > shim/clang++
chmod +x shim/clang shim/clang++
ln -sf "$TC/llvm-ar" shim/ar; ln -sf "$TC/llvm-ranlib" shim/ranlib
ln -sf "$TC/llvm-strip" shim/strip; ln -sf "$TC/llvm-nm" shim/nm
ln -sf "$TC/llvm-readelf" shim/readelf

# 2) fresh essentia clone at the pinned commit (local clone is fine)
git clone --no-hardlinks ~/essentia-build/essentia essentia
cd essentia && git checkout b9fa6cb674ca43dfb94d28d293aeda441c6745db
# apply the SAME src/wscript Eigen patch as arm64 (EIGEN3_INCLUDE env-driven)

# 3) configure + build (IDENTICAL flags to arm64; arch comes from the shim)
export PATH=$BASE/shim:$PATH
export EIGEN3_INCLUDE=$BASE/eigen-3.4.0
export CC=clang CXX=clang++ AR=ar RANLIB=ranlib STRIP=strip
python3 ./waf configure --cross-compile-android --lightweight= --fft=KISS \
        --ignore-algos=LPC --build-static --prefix=$BASE/install
python3 ./waf -j$(nproc)
python3 ./waf install      # -> $BASE/install/lib/libessentia.a (x86-64 ELF64)

# verify: `file install/lib/libessentia.a` members -> "Advanced Micro Devices X86-64"
#         clang++ -v reports Target: x86_64-unknown-linux-android31

# 4) copy ONLY the .a into the module (headers are arch-independent, already vendored)
cp install/lib/libessentia.a \
   /mnt/c/dev/RadioAI/android/dsp/src/main/cpp/essentia/lib/x86_64/libessentia.a
```

### Module wiring (per-ABI)
- `dsp/src/main/cpp/CMakeLists.txt` already linked
  `essentia/lib/${ANDROID_ABI}/libessentia.a` — no change needed; arm64-v8a and
  x86_64 each pick up their own `.a` automatically.
- `dsp/build.gradle.kts` and `app/build.gradle.kts`: `abiFilters` now build BOTH
  `arm64-v8a` and `x86_64`.
- Build: `.\gradlew :app:assembleDebug`. The JNI `.so` is compiled by the
  **Windows** NDK (27.0.12077973) for each ABI and statically links the matching
  prebuilt `libessentia.a`.

### Verified result
`app-debug.apk` contains both `lib/arm64-v8a/libkolaidsp.so` (AArch64) and
`lib/x86_64/libkolaidsp.so` (X86-64 ELF64, ~7.3 MB). The x86_64 `.so` has NO
`libessentia.so` in its `NEEDED` list (only liblog/libm/libdl/libc) — essentia
is statically linked in (`.text` ~4.8 MB) — and exports
`Java_ai_kolai_dsp_KolaiDsp_analyzePcmJson`.

### Gotchas (x86_64-specific)
- The waf banner still prints "Cross-compiling for Android ARM" even for x86_64
  — that string is hardcoded in `wscript`; ignore it. The real arch is whatever
  the shim's `--target=` says (confirm with `clang++ -v`).
- No new compile errors vs arm64; same harmless `-Wundefined-var-template`
  warning from `algorithmfactory_impl.h`.