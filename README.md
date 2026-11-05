# PLaMo Translate (Android)

**PLaMo 3 Translate 0.8B** offline translator for Android.

- On-device inference: kotlinllamacpp (LlamaHelper) + GGUF
- Camera OCR: CameraX + ML Kit (Japanese / Latin)
- Pages: Translate / Camera (swipe)
- Model download from Hugging Face (`plamo-3-translate-0.8b-Q4_K_M.gguf` ~550MB)

## Flow

1. Download model
2. Load engine
3. Translate offline

See GitHub Actions workflow `Build Debug APK` for CI APK artifacts.

## License note

Model weights: PLaMo Community License. App code: MIT-style free use.
