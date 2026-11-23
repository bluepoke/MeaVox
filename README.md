# MeaVox

An Android karaoke and singing-practice app. Find a music video, remove the vocals, sing over the instrumental with the lyrics on screen, and export your performance.

> **Status:** early development. The design is settled (see [docs](#documentation)); the Gradle project is scaffolded, implementation has not started yet.

## Features (planned for v1)

- **Browse sources**: search music videos on YouTube, SoundCloud, Bandcamp, PeerTube and others through [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), or import local files.
- **Vocal removal**: on-device source separation into an instrumental and a vocal stem. You choose between a fast, a medium and a high-quality model, and set which one is used by default.
- **Sing and record**: low-latency playback and recording on one sample-accurate timeline.
- **Real-time monitoring**: hear your own voice dry in wired or USB headphones. Not supported over Bluetooth, because its latency is too high.
- **Transpose**: shift the key by ±6 semitones without changing the tempo, in real time.
- **Lyrics**: fetched from [LRCLIB](https://lrclib.net), shown as pages or karaoke-style synced lines. Includes an offset adjustment and a tap-to-sync editor.
- **Takes and mixing**: record many takes per song and mix them non-destructively, with levels, alignment nudge, a guide vocal and vocal effects (reverb, compression, high-pass).
- **Export**: WAV, MP3 or M4A/AAC with tags, saved to `Music/MeaVox/` or shared.

## Architecture at a glance

| Layer | Technology |
|---|---|
| UI | Kotlin, Jetpack Compose |
| Audio Engine | C++ on [Oboe](https://github.com/google/oboe) (AAudio), full-duplex |
| Transpose | [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) |
| Separation | On-device ML models (ONNX Runtime / LiteRT) |
| Persistence | Room; audio stored as FLAC |
| Background work | WorkManager (resumable track pipeline) |

Modules:

- `core`: domain core in pure Kotlin (model, use cases, and the ports `Source`, `Separator`, `LyricsProvider`, `AudioEngine`); its test fixtures hold in-memory fakes for every port
- `data`: Room database and app-private file store, implementing the core's interfaces
- `app`: Android application (UI, wiring), in two flavors: `network` (with network Sources) and `local` (Local File Source only, Play-Store-safe; see ADR 1)
- `engine`: native Audio Engine (C++ via CMake and the NDK)
- `separation`: the `Separator` in pure Kotlin: the Separation Model catalogue and its download, DSP, and chunked Separation checkpointed per segment, on ONNX Runtime (ADR 15)
- `spikes/*`: throwaway spikes that validate ADRs on hardware ([`audio-engine`](docs/spikes/audio-engine-spike.md), [`separation`](docs/spikes/separation-spike.md)); not shipped

## Documentation

- [`GLOSSARY.md`](GLOSSARY.md): domain glossary (Track, Take, Mix, Session, Separation, …)
- [`docs/adr/`](docs/adr/README.md): architecture decision records
- [`docs/hardware-checklist.md`](docs/hardware-checklist.md): manual checks on a real device for the Audio Engine

## Building

Requirements:

- Android Studio (or a JDK 17+ and the Android SDK)
- Android SDK platform 37

AGP downloads the NDK and CMake automatically on first build. CMake fetches Signalsmith Stretch and libFLAC from GitHub and LAME from SourceForge, so the first build needs network access.

```sh
./gradlew assembleNetworkDebug assembleLocalDebug   # both flavors
./gradlew installNetworkDebug                       # install on a connected device
./gradlew test                                      # unit tests on the JVM, incl. Room migrations (Robolectric)
./gradlew :app:lintNetworkDebug                     # Lint, incl. untranslated or hardcoded UI text
```

CI (GitHub Actions) runs the same build and tests on every push and pull request.

Without Android Studio's environment, point Gradle at a JDK first, for example its bundled runtime:

```sh
export JAVA_HOME=/path/to/android-studio/jbr
```

Target platform: Android 10+ (minSdk 29), `arm64-v8a` (plus `x86_64` for the emulator).

## Known limitations

- Playback decodes the whole Original Audio into memory (float stereo, about 11.5 MB per minute at 48 kHz). That's fine for songs, but long videos use a lot of memory. Streaming from disk comes with the stored-stems work (ADR 8).
- A file the device can't decode is still imported as a Downloaded Track; it fails when played. A Failed pipeline state comes with the download pipeline. If decoding fails part-way through a file, the part decoded so far stays playable.
- Until Latency Calibration (#9) lands, Takes are aligned with the latency the engine reports when recording starts, which can be off by a few milliseconds. Output xruns during a Take are corrected (ADR 13). Input overruns are not: they are rare, because the input buffer holds twice the output's capacity.
- While a Take is recorded or reviewed, its audio is held in memory (mono float, about 11.5 MB per minute at 48 kHz) on top of the Backing Audio.

## Distribution

MeaVox is distributed outside the Google Play Store (GitHub releases / F-Droid), because extracting audio from streaming platforms conflicts with Play policies. You are responsible for complying with the terms of the platforms you use and with copyright law.

## License

[GPL-3.0-or-later](LICENSE). Required because NewPipeExtractor is GPL-3.0; see [ADR 11](docs/adr/0011-license-gpl-3.md).
