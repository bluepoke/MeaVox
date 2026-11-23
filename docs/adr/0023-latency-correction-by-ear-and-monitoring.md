# 23. Latency Correction by ear, and Monitoring

- Status: Accepted (amends ADR 4, ADR 7, ADR 13, ADR 16)
- Date: 2026-11-23

## Context
ADR 4 planned a one-time **Latency Calibration** per output route, and ADR 13 kept the spike's click detection for it: clicks played and detected in the microphone, the median offset stored per route. #9 was to build it, with Monitoring.

Takes are already aligned with the round trip the platform reports when recording starts (ADR 13). On the speaker that matched the measured round trip within 2 ms in the spike; over wired headphones the platform reported nothing, and the engine falls back to an estimate from its buffer sizes. The Mix Editor's Vocal Nudge (ADR 7) already moves a Take by ear. A guided click test needs an earbud held against the microphone in a quiet room, a native click detector and a flow around it, for a correction most routes barely need.

## Decision
- **No click calibration.** Instead each output route (speaker, wired, USB, Bluetooth, other) and output path (normal, low-latency while Monitoring is on; ADR 16) has a **Latency Correction**: milliseconds added to the reported round trip, set **by ear in Review** ("Timing", ±400 ms in 5 ms steps, shown like the Vocal Nudge with later positive). The Take in Review moves at once, and the value is stored for its route and path.
- **A Take keeps the Latency Correction it was kept with** (Room version 17). Correcting the route later doesn't move Takes kept before, so a new device or interface on the same route can't shift them. Review and kept Takes play with it, and so do Mixes and their Export, with the Vocal Nudge on top.
- **The prompt on an uncorrected route** is a hint in Review, not a dialog: "Check the timing: does your voice sit on the backing? …", shown until a correction is stored for the route and path. Setting it, or keeping a Take as it sounded, stores it.
- **Monitoring** (ADR 4, ADR 13): the dry, untransposed microphone at a level (kept across Sessions, 50 % at first), added to the output in free playback (also paused, to set the level), in the countdown and while recording. It is never in the Take, and silent while a Take plays back: in Review (also paused), a kept Take and a Mix Preview. It is **off at the start of every Session** and switched only in free playback. Switching it on opens the microphone, asking for the permission first. If the microphone or the output won't open on the low-latency path, the engine goes back to the normal path with Monitoring off. It is available over **wired and USB** headphones only. On the speaker, on Bluetooth and on unknown outputs it is disabled with the reason. Switching it reopens the Session output on the **low-latency path** while it is on and on the normal path otherwise (ADR 16), which pauses playback. A **route change** switches it off; the engine reopens on the new route and the Session goes on (a recording ends in Review, as before).
- **The output panel** shows the route and its round trip (reported plus the Latency Correction, "corrected by ear" once one is stored), a warning when Monitoring is on and the round trip is above 40 ms (provisional, ADR 13; only the low-latency path, ADR 16), and on USB the hint about Hardware Monitoring.
- **Without headphones** (speaker or an unknown output), the Session warns before and while recording that the microphone records the backing too.

## Considered Options
- **Click calibration as in the spike** (ADR 13). Rejected by the project owner: it measures only the system (a singer's own timing doesn't leak in) and is more precise (−3 to 0 ms in the spike), but the guided flow and the native detector cost more than the correction is worth on most routes, and a Take can be nudged in its Mix anyway.
- **Takes follow the route's current correction.** Rejected: a later correction for another device on the same route would shift Takes that were right.
- **A dialog before recording on an uncorrected route.** Rejected: the timing can only be judged by listening to a Take.

## Consequences
- A correction by ear includes the singer's own timing in the Take it was set on. That is accepted; the Vocal Nudge refines each Mix.
- The round trip shown is the platform's estimate plus the correction, not a measurement. The 40 ms warning uses it.
- Monitoring and its path switch are checked on a device (hardware checklist, #9); the engine's tests feed the microphone to a headless engine.
