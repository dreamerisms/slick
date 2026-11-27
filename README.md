# Slick

Android shell for the Slick audio-reactive liquid visualizer.

- `web/slick.html` (Slick, liquid), `web/hyper.html` (Hyper, particles) and `web/signal.html` (Signal, a topographic
  survey of nearby Wi-Fi and Bluetooth), `web/glyph.html` (Glyph, an ASCII-rendered 3D object that turns with
  the phone) and `web/trace.html` (Trace, the song drawn as a live treemap of bars in layers of ink on trace paper,
  2D canvas) are the scene sources. The Cycle chip hops between scenes at random on a timer.
  Switch scenes from the strip at the top of the panel; the app remembers the last one.
- Every scene has a Motion driver: **Gyro** follows the phone (or the car), **Beat** ignores the sensors and lets
  kicks, snares and bass move the scene instead.
- `app/src/main/assets/index.html` is generated from it. Regenerate the app pages with `tools/sync-scene.sh`.
- `AudioEngine.kt` holds the two native audio routes and the band contract the scene reads:
  - **Phone audio**: Android's Visualizer on the global output mix. No consent dialog.
  - **Capture**: playback capture through a foreground service. Full-quality PCM, asks each session.
- `SignalScanner.kt` feeds the Signal scene: Wi-Fi scan results and Bluetooth LE advertisements, held in memory
  only while that scene is open. Nothing is stored or sent anywhere.
- Every push to `main` builds a debug APK on GitHub Actions and publishes it under Releases, labeled from
  `release.txt`: a new number for feature releases (16), a letter for fixes on top of one (16a, 16b).
- The debug signing key in `app/keystore/` is a throwaway key so updates install over each other.
  Never use it for a Play Store build.
