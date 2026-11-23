# 4. Native Oboe audio engine owns the Session timeline; Monitoring is wired-only and dry

- Status: Accepted, amended by [ADR 13](0013-monitoring-and-take-alignment-after-engine-spike.md) (latency warning instead of a latency target, Hardware Monitoring hint, timestamp-based Take alignment), [ADR 16](0016-session-output-normal-path-unless-monitoring.md) (normal output path unless Monitoring is on), [ADR 18](0018-video-streaming-only-backlog.md) (video is a streaming-only nice to have) and [ADR 23](0023-latency-correction-by-ear-and-monitoring.md) (a Latency Correction by ear instead of Latency Calibration)
- Date: 2026-11-14

## Context
Monitoring needs a mic-to-ear round trip under about 20–30 ms. ExoPlayer and MediaPlayer can't do low-latency full-duplex audio and don't give a shared clock for playback and recording. Bluetooth adds roughly 150–300 ms of latency.

## Decision
- The UI is **Kotlin + Jetpack Compose**. A **C++ audio engine on Oboe (AAudio)** owns all Session audio: Backing Audio playback, real-time Transpose, mic capture, Monitoring, and writing the Take, all on **one sample-accurate timeline**.
- **Monitoring is optional and off by default.** It is available only on **wired/USB** output, and is disabled or shown with a warning when the output is Bluetooth.
- The monitored voice is **dry** (no effects) in v1.
- A one-time per-device-route **Latency Calibration** (loopback or clap test) measures the input/output offset. That offset is applied to Takes so they line up in the Mix.
- **Video is a follower**: ExoPlayer plays the video muted and slaves to the audio engine's clock.

## Consequences
- The project needs the NDK, a JNI bridge, and native build tooling (CMake).
- The Transpose algorithm has to run in real time inside the engine (see a later ADR).
- Recording over Bluetooth playback is supported, but needs its own calibrated offset.
- Video sync is best-effort. Small drift is corrected by seeking or adjusting playback speed.
