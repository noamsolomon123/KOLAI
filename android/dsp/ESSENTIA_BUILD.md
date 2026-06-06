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