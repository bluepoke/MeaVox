# 16. The Session plays through the normal output path unless Monitoring is on

- Status: Accepted (amends ADR 4; amended by ADR 23)
- Date: 2026-11-17

## Context
ADR 4 gave the Session a low-latency output (AAudio `LowLatency`, exclusive) for Monitoring. On the Galaxy Tab S6 Lite, playback in a Session sounded thin and tinny, while the same Instrumental sounded full on Track detail. Track detail uses the normal output path, and devices apply their speaker processing (here Dolby Atmos) only there. Monitoring isn't built yet (#9). It is optional, off by default and not a priority: a microphone with Hardware Monitoring is the better choice anyway. Takes don't need a low-latency output, because they are aligned by stream timestamps and Latency Calibration (ADR 13).

## Decision
- With Monitoring off, the Session output uses the **normal path** (`PerformanceMode::None`, shared), the same as plain playback. The microphone input stays low-latency.
- Only when Monitoring is switched on (#9) does the Session reopen its output on the low-latency path, accepting the device's thinner sound for the shorter round trip.

## Note: checked on device (2026-11-17)
On the Galaxy Tab S6 Lite (Android 14), the Session now sounds as full as Track detail, and loopback Takes still line up with the playback (hardware checklist, #8 item 6).

## Consequences
- The output latency in a Session is higher (not measured yet; the engine logs it when the Session opens). It only shifts the reported latency that Take alignment already corrects. Loopback alignment (hardware checklist, #8 item 6) had to be checked again on the new path (see the note above).
- Larger output buffers leave more headroom for real-time Transpose, so fewer xruns are expected.
- #9 has to switch the output path when Monitoring is toggled, and its latency warning (ADR 13) applies to the low-latency path only. Built so in #9 (ADR 23); since the two paths' latencies differ, the Latency Correction is per route and path.
