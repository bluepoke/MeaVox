# 8. Storage formats and Export (WAV, MP3 via LAME, M4A/AAC via MediaCodec)

- Status: Accepted, amended by [ADR 18](0018-video-streaming-only-backlog.md) (no offline video; video is streaming only) and [ADR 24](0024-two-apps-meavox-and-meavox-max.md) (LAME vendored)
- Date: 2026-11-14
- Note: the export folder follows the app name and changed with the rename to MeaVox ([#40](https://github.com/bluepoke/MeaVox/issues/40)); exports made before stay in the old folder.

## Decision
**Internal storage (app-private, per Track):**
- **Original Audio** is kept as downloaded (Opus/AAC), with no re-encode.
- **Instrumental / Vocal Stem** are stored as **stereo FLAC**, per Separation Model.
- **Takes** are stored as **mono FLAC** at the Audio Engine's native rate (typically 48 kHz).
- **Video is streamed by default.** An optional per-Track "keep video offline" setting downloads a copy capped at ≤720p.
- Metadata (Tracks, Takes, Mixes, Lyrics, Mix Settings) lives in **Room**.
- A **storage screen** shows per-Track usage and lets the user delete stems, video or Takes. Stems can be regenerated as long as the Original Audio exists.

**Export formats:**
- **WAV**: 16-bit PCM (24-bit optional).
- **MP3**: via **LAME** (LGPL) through the NDK. Default VBR V2, with 320 kbps CBR optional. Tagged with **ID3** (title, artist "feat. <singer>", Cover Art from the Source's picture; see the note on #42).
- **M4A/AAC**: via the platform **MediaCodec** encoder, with MP4 metadata tags.
- Output goes to **MediaStore** `Music/MeaVox/`, and can also be shared through the Android share sheet.

## Note: Take sample rate (#8)
The Session timeline runs at the **loaded Backing Audio's sample rate**, not the device's native rate. The Audio Engine opens its streams at that rate and Oboe resamples to and from the device, so a band-limited resampler doesn't have to live in the app. Takes are therefore stored at the Backing Audio's rate, usually 44.1 or 48 kHz. Separation keeps stems sample-aligned with the Original Audio, at the same rate, so a Take fits both the Original Audio and the Instrumental. Takes are written as 24-bit FLAC through libFLAC.

## Consequences
- The NDK build bundles LAME and a FLAC codec (libFLAC, BSD). Keep LGPL compliance for LAME (dynamic linking, offer the source).
- Offline Sessions without a kept video fall back to audio-only.

## Note: Backing Export built in #24 (2026-11-19)
- The Track's screen (Track detail and the Session in one, ADR 20) has a **Backing track → Export** entry once the Original Audio is stored. It offers the Instrumental the Track uses (only once it is Ready with one) and the Original Audio. It starts at the Backing Audio the Track plays and at its Last Transpose. A Transpose changed there is for that export only.
- The Offline Render renders the Backing Audio whole, from its first to its last frame, with the same `BackingRenderer` as playback (bypassed at Transpose 0, ADR 5). The length is only known at the end, so the WAV header is written over once it is.
- **WAV only for now** (16-bit, or 24-bit), like the Mix Export. MP3, M4A/AAC and cover art come with #15 for both. A WAV carries the title and the artist in a RIFF INFO list (INAM, IART); MediaStore gets them too.
- Titles name the layer and the Transpose: "Title (Instrumental, +2)", "Title (Original, −1)", no Transpose at 0. A Vocal-Free Track's Original Audio is called "Instrumental" (ADR 14).

## Note: MP3 and M4A/AAC built in #15 (2026-11-23)
- Both Exports (Mix and Backing) offer **MP3** (VBR V2 by default, or 320 kbps CBR), **M4A** (AAC-LC, 256 kbps) and **WAV** (16-bit or 24-bit). MP3 is pre-selected. Encoders, tags and MediaStore output live in their own `:export` module.
- **LAME 3.100** is built from its release tarball as its own shared library (`libmp3lame.so`). The JNI library links it dynamically, as the LGPL requires. The source is the unmodified tarball, pinned by its SHA-256 in `export/src/main/cpp/CMakeLists.txt`. Its licence is in [`docs/licenses/LGPL-2.0-LAME.txt`](../licenses/LGPL-2.0-LAME.txt); #21 lists it in the app. LAME writes its Xing/LAME tag frame, which carries the length and the encoder delay, and the frame is written over once the stream ends. The ID3 tag is written in Kotlin as **ID3v2.4** with UTF-8 text. v2.3 was tried first, but Android collapses its UTF-16 frames that hold only Latin-1 characters to 8 bits, and its charset detector then garbled the other frames: "– Für" came back as "â€“ FĂĽr" on an Android 14 tablet.
- **M4A** is muxed by the app, not by MediaMuxer, which can't write iTunes metadata (`©nam`, `©ART`). `moov` follows `mdat`, because only the end of the render knows the length. The encoder's priming and padding (a few AAC frames) are not trimmed with an edit list.
- A Mix Export's artist is "<artist> feat. <singer>". The **singer's name** is a setting ("Your name"). Without it, the tag holds the artist alone, and without an artist, the name alone. Backing Exports keep the original artist.
- **Cover art** came later, with [#42](https://github.com/bluepoke/MeaVox/issues/42) (see its note below).

## Note: Cover Art built in #42 (2026-11-27)
- A Track added from a Source keeps where the Source's **largest** picture of the item is (`coverUrl`, Room version 18), not the small thumbnail the search list shows. The Downloading step downloads it once the Track is Downloaded (so a run cut short only loses the picture, never the audio) and stores it with the Track's files, so Exports work offline and the picture goes when the Track is deleted. An imported file's embedded picture (`MediaMetadataRetriever`) is stored the same way during the import.
- Every picture is decoded and stored as a **JPEG** of at most 1000 px on its longer side: MP4's `covr` only takes JPEG or PNG, and Sources also serve WebP.
- The picture is best-effort: one that can't be downloaded or read leaves the Track without Cover Art, never Failed, and isn't retried. Tracks added before #42 have none; their Exports stay without one.
- MP3 Exports carry it as an ID3v2.4 `APIC` frame (front cover, `image/jpeg`), M4A Exports as a `covr` item (JPEG data). WAV Exports carry none.

## Note: LAME vendored (#21, 2026-11-27)
LAME 3.100 is no longer downloaded at build time: the parts of the release tarball the build uses are vendored unmodified in
`third_party/lame-3.100/` (provenance and how to check them in `third_party/lame-3.100.md`), so the build fetches nothing, as
F-Droid requires (ADR 24). It is still built as its own `libmp3lame.so` and linked dynamically; the vendored source is the
LGPL source offer the app and the README point to.
