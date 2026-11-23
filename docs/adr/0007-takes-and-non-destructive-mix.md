# 7. Many Takes per Track; non-destructive Mix of one Instrumental and one Take

- Status: Accepted
- Date: 2026-11-14

## Decision
- **A Track has many Takes.** Each is kept until the user deletes it.
- **A Mix = one Backing Audio (Instrumental or Original Audio) + one Take + Mix Settings.** No comping and no multi-Take layering or duets in v1.
- **Mix Settings**: backing level, vocal level, **Vocal Nudge** (±200 ms on top of Latency Calibration), guide-vocal level (Vocal Stem, default 0), and **Vocal Effects**.
- **Vocal Effects** are applied at mix time only (Monitoring stays dry, ADR 4). v1 has a small preset set: reverb (Dry/Room/Hall), gentle compression, and a high-pass filter.
- Mixes are **non-destructive**: Takes are never modified, Mix Settings are persisted, and audio is rendered only for preview or Export.
- A Mix references its Instrumental by Track and Separation Model, so it can switch to a re-separated Instrumental.

## Consequences
- Preview and Export share the same offline render path (Audio Engine DSP + Signalsmith with the Take's Transpose, ADR 5).
- Storage grows with the number of Takes. Takes should be stored compactly (see the storage ADR).

## Note: built in #14 (2026-11-19)
- **Keeping a Take creates a default Mix** over the Backing Audio the Take was sung over, at the current **Review Balance** (backing −20 dB unless changed, vocal 0 dB, no Vocal Nudge), so it sounds like Review. More Mixes come from a Take's "New Mix"; a Track can have any number. Deleting a Take deletes its Mixes; exported files stay.
- **A Mix spans what Review plays**: from the Take's Start Position until its last sample has sounded, not the whole song.
- **Alignment**: Take sample *i* sounds at Start Position + *i* − (Latency Calibration offset − Vocal Nudge). Until Latency Calibration (#9), the offset is the engine's estimate from recording, as in Review.
- **The preview plays in real time** through the Session's Audio Engine, like Review, so it starts at once and level and Nudge changes are heard live. It plays the Mix's own Backing Audio and returns to the Session's afterwards. The **Offline Render** (Export) shares the DSP code with it (`BackingRenderer`: Signalsmith Stretch with the same preset, read-ahead and priming, and the same Take mixing), which makes preview and Export sound the same. That replaces "preview uses the Offline Render" above: rendering first would take seconds to tens of seconds before anything plays, and again after every change.
- The Offline Render streams the Backing Audio through and keeps only a short window of it, so a Session that holds the same song in memory isn't doubled.
- A Mix keeps the Instrumental of its Separation Model while those stems are stored. Once they are gone after a re-separation, it switches to the Track's current Instrumental. While a Track has no Instrumental (e.g. its stems are being made again), a Mix on the Instrumental can't be previewed or exported.
- **Export to WAV runs on the Track's screen**, with progress and Cancel; leaving the Mix Editor or the screen cancels it and removes the unfinished file. It is not yet a WorkManager job (spec #1); that can follow when MP3/M4A (#15) make Exports longer.
- The Guide Vocal level and Vocal Effects came with #16 (note below).

## Note: Vocal Effects and the Guide Vocal built in #16 (2026-11-23)
- **Vocal Effects** are a chain on the Take, in this order: a high-pass filter (100 Hz), gentle compression (3:1 above −18 dBFS, soft knee, +3 dB make-up) and a reverb (Dry, Room or Hall, the Freeverb structure). They work on the Take as recorded, before the vocal level. A new Mix has none, so it sounds like Review; existing Mixes keep sounding as they did.
- The chain (`VocalChain`) is shared by the Mix Preview and the Offline Render through `mixTake`, as the Backing Audio DSP is. It is fed one Take sample per timeline frame from a fresh state where the Mix starts, so an Export of the same settings sounds like an uninterrupted preview. In the preview, preset changes are heard at once. With no effects, `mixTake` mixes the Take exactly as before.
- **A reverb rings on**: the Mix runs on for its tail (Room 1 s, Hall 2.5 s) after the Take's last sample, in the preview and the Export, with the Backing Audio playing on.
- **The Guide Vocal** in a Mix is the Vocal Stem of the Mix's Instrumental. It is added to the Instrumental *before* the stretcher, so it is transposed with it by the one Signalsmith Stretch, and its level follows the backing level. Its own level runs from 0 (none, the default) to 1 (the vocals as Separation found them, so the Mix's backing sounds like the Original Audio). It can't be set over the Original Audio, which has the vocals already, nor on a Vocal-Free Track, which has no Vocal Stem (ADR 14); the Mix Editor shows why.
- The Mix Preview loads the Vocal Stem into the Audio Engine only when the level is above 0, and drops it when the preview ends. The Offline Render reads it from its FLAC in step with the Backing Audio, so it never holds the whole stem. Both read it through libFLAC, exactly as Separation wrote it.
- Review and Monitoring stay dry and without the Guide Vocal.
