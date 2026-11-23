# 8. Storage formats and Export (WAV, MP3 via LAME, M4A/AAC via MediaCodec)

- Status: Accepted, amended by [ADR 18](0018-video-streaming-only-backlog.md) (no offline video; video is streaming only)
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
- **MP3**: via **LAME** (LGPL) through the NDK. Default VBR V2, with 320 kbps CBR optional. Tagged with **ID3** (title, artist "feat. <singer>", cover art from the thumbnail).
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
- **Cover art** comes with [#42](https://github.com/bluepoke/MeaVox/issues/42): a Track doesn't keep its thumbnail yet.
