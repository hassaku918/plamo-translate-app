# PLaMo Translate (Offline)

Android offline translator using **PLaMo 3 Translate 0.8B (GGUF)** via the
official llama.cpp, compiled from source into the in-repo `:llamajni`
module, with CameraX + ML Kit OCR.

Translation prompts are built in the official **PFML3** format (see the
[model card](https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF)):
prompts start with `<|plamo:tag|>dataset<|plamo:msg|>translation`, languages
are passed as codes via `<|plamo:key|>lang<|plamo:val|>…`, Japanese style is
passed via the `form` key (`plain` / `polite`), and generation stops on
`<|plamo:tag|>` / `<|plamo:bos|>`.

## Features
- Fully offline GGUF inference (after one-time model download)
- Text translation with language selection (EN/JA swap; PFML3 language codes)
- Plain / polite style for Japanese output (PFML3 `form` key)
- Camera OCR (Japanese + Latin) with selectable text
- Model download from Hugging Face with size / GGUF magic checks.
  Default quant is **Q4_K_M** (~573MB, the model card's default);
  Q4_0 (~548MB) is the only automatic fallback. Smaller quants
  (Q3_K_M / Q2_K) are not downloaded automatically because their
  translation accuracy drop is clearly larger on the 0.8B model.
  A model file already on the device is used as-is regardless of quant.

## Build APK (GitHub Actions)
1. Open [Actions](https://github.com/hassaku918/plamo-translate-app/actions)
2. Workflow **Build Debug APK** → download artifact `plamo-translate-debug-apk`

The workflow also runs the unit tests (`:app:testDebugUnitTest`, including
the PFML3 prompt tests) before building.

## Local build
The native build expects a llama.cpp checkout in the repo root
(`<repo>/llama.cpp`). CI pins llama.cpp to commit
`e95426f16982054c379ca7bdc8186996dd22f0fe` — use the same commit locally:

```bash
git init llama.cpp
git -C llama.cpp remote add origin https://github.com/ggml-org/llama.cpp.git
git -C llama.cpp fetch --depth 1 origin e95426f16982054c379ca7bdc8186996dd22f0fe
git -C llama.cpp checkout --detach FETCH_HEAD

./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/
```

Requirements: JDK 17, Android platform 36, build-tools 35.0.0,
CMake 3.22.1, NDK 27.0.12077973.

## First run
1. Install the debug APK
2. Tap download for the GGUF model (~573MB for the default Q4_K_M, Wi‑Fi recommended)
3. After load, inference runs offline (no network required for translate/OCR)

## Stack
- Kotlin 2.3 / Compose Material3 / Hilt
- CameraX + ML Kit Text Recognition (JA/Latin)
- Official llama.cpp (pinned commit, see above) via the in-repo
  `:llamajni` JNI module (`NativeLlama`: load / unload / complete)

## Privacy
- `allowBackup=false`
- No analytics; model stays on device after download
