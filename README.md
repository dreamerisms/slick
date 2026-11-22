# Slick

Android shell for the Slick audio-reactive liquid visualizer.

- `web/slick.html` (Slick, liquid), `web/hyper.html` (Hyper, particles) and `web/signal.html` (Signal, a topographic
  survey of nearby Wi-Fi and Bluetooth) are the scene sources (WebGL2).
  Switch scenes in the Source tab; the app remembers the last one.
- `app/src/main/assets/index.html` is generated from it. Regenerate the app pages with `tools/sync-scene.sh`.
- `AudioEngine.kt` holds the two native audio routes and the band contract the scene reads:
  - **Phone audio**: Android's Visualizer on the global output mix. No consent dialog.
  - **Capture**: playback capture through a foreground service. Full-quality PCM, asks each session.
- `SignalScanner.kt` feeds the Signal scene: Wi-Fi scan results and Bluetooth LE advertisements, held in memory
  only while that scene is open. Nothing is stored or sent anywhere.
- Every push to `main` builds a debug APK on GitHub Actions and publishes it under Releases.
- The debug signing key in `app/keystore/` is a throwaway key so updates install over each other.
  Never use it for a Play Store build.
