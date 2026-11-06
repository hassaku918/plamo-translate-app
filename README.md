# PLaMo Translate (Offline)

Android offline translator using **PLaMo 3 Translate 0.8B (GGUF Q4_K_M)** via `llamacpp-kotlin`, with CameraX + ML Kit OCR.

## Features
- Fully offline GGUF inference (after one-time model download)
- Text translation (EN/JA and related tags)
- Camera OCR (Japanese + Latin) with selectable text
- Model download from Hugging Face with size / GGUF magic checks

## Build APK (GitHub Actions)
1. Open [Actions](https://github.com/hassaku918/plamo-translate-app/actions)
2. Workflow **Build Debug APK** → download artifact `plamo-translate-debug-apk`

Or locally:
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

## First run
1. Install the debug APK
2. Tap download for the GGUF model (~500MB+, Wi‑Fi recommended)
3. After load, inference runs offline (no network required for translate/OCR)

## Stack
- Kotlin 2.3 / Compose Material3 / Hilt
- CameraX + ML Kit Text Recognition (JA/Latin)
- `io.github.ljcamargo:llamacpp-kotlin:0.4.0`

## Privacy
- `allowBackup=false`
- No analytics; model stays on device after download
