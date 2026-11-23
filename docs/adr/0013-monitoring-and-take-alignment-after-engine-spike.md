# 13. Monitoring latency warning, Hardware Monitoring hint, timestamp-based Take alignment

- Status: Accepted (amends ADR 4; amended by ADR 16, ADR 23)
- Date: 2026-11-15

## Context
The Audio Engine spike (#2, findings in `docs/spikes/audio-engine-spike.md`) measured ADR 4's design on one device: a Galaxy Tab S6 Lite (2022), Snapdragon 720G, Android 14. That is the weak end of the target range. No low-latency device could be measured.

- AAudio granted **shared** streams only (no exclusive/MMAP path) on every route. The round trip was about **83 ms** on the speaker and wired headphones, and **60 ms** on a USB mic. Burst and buffer account for only about 12 ms; the rest is the platform pipeline. ADR 4's assumption of a ≤ 20–30 ms round trip does not hold on such devices.
- Software Monitoring at about 83 ms was rated **3 of 5** ("noticeable but OK") with closed headphones. A hard cut-off at 30 ms would remove a feature that singers would still choose to use.
- The USB mic tested has **Hardware Monitoring** (zero-latency, on its own headphone jack, cannot be switched off). With software Monitoring also on, the user hears both copies.
- Click-based Latency Calibration matched Oboe's reported latency. With it applied, acoustic loopback Takes were aligned within **−3 to 0 ms** with **no continuous drift** when input and output run through the same device.
- On wired, one loopback Take **jumped by +4.0 ms (exactly one 192-frame burst)** mid-Take and stayed there. This was most likely an xrun. The spike derives the timeline by counting callbacks, so every xrun permanently shifts the rest of the Take by one burst.
- Real-time Transpose (Signalsmith `presetCheaper`) plus Monitoring peaked at **70 % callback CPU**, with 1 xrun in 3 minutes on that device.

## Decision
- **Monitoring is not gated on a latency target.** It stays optional, off by default, dry, and available on wired/USB only (Bluetooth and speaker excluded, as in ADR 4).
- The Session shows the **measured round trip** of the current route. Above **40 ms** (*provisional*) it shows a warning that Monitoring will be audibly delayed on this device. Without a calibration for the route, the warning is based on the reported latency.
- When the output is a USB device, the app hints that an interface with its own headphone jack usually has **Hardware Monitoring**, and that software Monitoring should stay off. Software Monitoring is never switched on automatically.
- **Takes are aligned using stream timestamps, not callback counting.** The Audio Engine maps input and output frames to a common clock using the streams' frame position and time (`getTimestamp`). At minimum it detects xruns and corrects the Take index by the frames lost, so an xrun can't permanently shift the rest of a Take.
- Input and output on **different devices** (separate clocks) remain unmeasured. Until they are measured, the engine must detect and correct drift between the input and output frame rates over a Take, or reject that combination for recording.
- **CPU headroom**: the production Transpose configuration must leave headroom on the weakest target device. The callback load target is ≤ 50 % with Transpose and Monitoring active. If `presetCheaper` cannot meet that, use a larger buffer for Sessions (latency matters less than glitches when Monitoring is off).

## Consequences
- The 40 ms threshold is a guess between the measured points (0 ms Hardware Monitoring = 5, about 83 ms = 3). It must be revisited once a low-latency device (exclusive/MMAP) has been measured with the spike protocol.
- Latency Calibration (click detection) was kept as the production approach. ADR 23 replaced it with a Latency Correction by ear, per route and output path, so its repeatability no longer needs confirming.
- Take alignment tests in the Audio Engine (Seam 2 and hardware checklists) must include an injected xrun and verify that the Take stays aligned.
- Clapping is unsuitable for measuring alignment (people clap 20–50 ms ahead of a regular click). Alignment is verified with acoustic loopback only.
