<h1 align="center"><img src="docs/logo/meavox-logo.svg" alt="MeaVox" width="420"></h1>

An Android karaoke and singing-practice app. Find a music video, remove the vocals, sing over the instrumental with the lyrics on screen, and export your performance.

It comes as two apps from this one code base ([ADR 24](docs/adr/0024-two-apps-meavox-and-meavox-max.md)):

| App | Sources | Get it from |
|---|---|---|
| **MeaVox** (`eu.schicht8.meavox`) | your own audio and video files | Google Play |
| **MeaVox Max** (`eu.schicht8.meavox.max`) | your own files, and YouTube, SoundCloud, Bandcamp, PeerTube and others through NewPipeExtractor | F-Droid and GitHub Releases |

> **Status:** early development. The design is settled (see [docs](#documentation)); the Gradle project is scaffolded, implementation has not started yet.

## Features (planned for v1)

- **Browse sources**: import local files, or (MeaVox Max) search music videos on YouTube, SoundCloud, Bandcamp, PeerTube and others through [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).
- **Vocal removal**: on-device source separation into an instrumental and a vocal stem. You choose between a fast, a medium and a high-quality model, and set which one is used by default.
- **Sing and record**: low-latency playback and recording on one sample-accurate timeline.
- **Real-time monitoring**: hear your own voice dry in wired or USB headphones. Not supported over Bluetooth, because its latency is too high.
- **Transpose**: shift the key by ±6 semitones without changing the tempo, in real time.
- **Lyrics**: fetched from [LRCLIB](https://lrclib.net), shown as pages or karaoke-style synced lines. Includes an offset adjustment and a tap-to-sync editor.
- **Takes and mixing**: record many takes per song and mix them non-destructively, with levels, alignment nudge, a guide vocal and vocal effects (reverb, compression, high-pass).
- **Export**: WAV, MP3 or M4A/AAC with tags and cover art, saved to `Music/MeaVox/` or shared. Covers come from the source or the imported file, or from a search on MusicBrainz.

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
- `app`: Android application (UI, wiring), in two flavors: `local` builds **MeaVox** (Local File Source only, for Google Play) and `network` builds **MeaVox Max** (with the NewPipe Sources); see ADR 1 and ADR 24
- `engine`: native Audio Engine (C++ via CMake and the NDK)
- `coverart`: the `CoverSearch` in pure Kotlin: the cover search on MusicBrainz and the Cover Art Archive (#51)
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

The native dependencies are in `third_party/`: Signalsmith Stretch, Signalsmith Linear and libFLAC as Git submodules, LAME 3.100 vendored ([`third_party/lame-3.100.md`](third_party/lame-3.100.md)). Clone with them, or fetch them once after cloning:

```sh
git clone --recursive https://github.com/bluepoke/MeaVox.git
git submodule update --init --recursive   # in an existing clone
```

AGP downloads the NDK (r28c) and CMake on first build. Apart from Gradle's own repositories (Google, Maven Central, JitPack for NewPipeExtractor), the build fetches nothing, as F-Droid requires.

```sh
./gradlew assembleLocalDebug assembleNetworkDebug   # both apps: MeaVox (local) and MeaVox Max (network)
./gradlew installNetworkDebug                       # install MeaVox Max on a connected device
./gradlew test                                      # unit tests on the JVM, incl. Room migrations (Robolectric)
./gradlew :app:lintNetworkDebug                     # Lint, incl. untranslated or hardcoded UI text
```

CI (GitHub Actions) runs the same build and tests on every push and pull request, and builds both release apps signed with a throwaway key.

### Release builds and signing

Release builds are signed from the environment only, never from a file in the repository. Per flavor (`local` for MeaVox, `network` for MeaVox Max), set either the Gradle properties `meavox.signing.<flavor>.storeFile`, `.storePassword`, `.keyAlias` and `.keyPassword` (e.g. in `~/.gradle/gradle.properties`), or the variables `MEAVOX_SIGNING_<FLAVOR>_STORE_FILE`, `…_STORE_PASSWORD`, `…_KEY_ALIAS` and `…_KEY_PASSWORD`. Without them, the release build is unsigned.

```sh
./gradlew bundleLocalRelease       # MeaVox as an App Bundle for Google Play, signed with the upload key
./gradlew assembleNetworkRelease   # MeaVox Max as an APK, signed with the release key
```

Pushing a tag `v<versionName>` (the `versionName` in `app/build.gradle.kts`, e.g. `v0.1.0`) builds MeaVox Max, signs it with the key in the repository secrets `MEAVOX_MAX_KEYSTORE_BASE64` (the keystore, base64), `MEAVOX_MAX_STORE_PASSWORD`, `MEAVOX_MAX_KEY_ALIAS` and `MEAVOX_MAX_KEY_PASSWORD`, and publishes it to GitHub Releases. What the Play Console asks for is in [`docs/play/`](docs/play/README.md), the F-Droid recipe draft in [`docs/fdroid/`](docs/fdroid/eu.schicht8.meavox.max.yml).

Without Android Studio's environment, point Gradle at a JDK first, for example its bundled runtime:

```sh
export JAVA_HOME=/path/to/android-studio/jbr
```

Target platform: Android 10+ (minSdk 29), `arm64-v8a` (plus `x86_64` for the emulator).

## Known limitations

- Playback decodes the whole Original Audio into memory (float stereo, about 11.5 MB per minute at 48 kHz). That's fine for songs, but long videos use a lot of memory. Streaming from disk comes with the stored-stems work (ADR 8).
- A file the device can't decode is still imported as a Downloaded Track; it fails when played. A Failed pipeline state comes with the download pipeline. If decoding fails part-way through a file, the part decoded so far stays playable.
- Takes are aligned with the latency the engine reports when recording starts, plus a Latency Correction set by ear in Review per output route and path (ADR 23). Output xruns during a Take are corrected (ADR 13). Input overruns are not: they are rare, because the input buffer holds twice the output's capacity.
- While a Take is recorded or reviewed, its audio is held in memory (mono float, about 11.5 MB per minute at 48 kHz) on top of the Backing Audio.

## Distribution

**MeaVox** is distributed on Google Play. **MeaVox Max** is distributed outside it, on F-Droid and GitHub Releases, because extracting audio from streaming platforms conflicts with Play policies. You are responsible for complying with the terms of the platforms you use and with copyright law.

## License

[GPL-3.0-or-later](LICENSE), for both apps. Required because NewPipeExtractor is GPL-3.0; see [ADR 11](docs/adr/0011-license-gpl-3.md).

The third-party work each app contains, and the Separation Models' weights, are listed with their licenses in the app (Settings → Third-party licenses); the texts are in [`docs/licenses/`](docs/licenses).

**LAME** (the MP3 encoder) is licensed under the GNU LGPL, version 2 or later. Both apps link it dynamically, as `libmp3lame.so`, so it can be replaced. Its complete source code, exactly as built into the apps, is in this repository at [`third_party/lame-3.100/`](third_party/lame-3.100), together with how to check it against the LAME 3.100 release; it is offered under the same terms, free of charge.
