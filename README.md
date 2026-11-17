# Slick

Android shell for the Slick audio-reactive liquid visualizer.

- `web/slick.html` is the scene source (WebGL2), the same file as the web preview.
- `app/src/main/assets/index.html` is generated from it. Regenerate it from the web
  version with `tools/sync-scene.sh web/slick.html`.
- `AudioEngine.kt` holds the two native audio routes and the band contract the scene reads:
  - **Phone audio**: Android's Visualizer on the global output mix. No consent dialog.
  - **Capture**: playback capture through a foreground service. Full-quality PCM, asks each session.
- Every push to `main` builds a debug APK on GitHub Actions and publishes it under Releases.
- The debug signing key in `app/keystore/` is a throwaway key so updates install over each other.
  Never use it for a Play Store build.
