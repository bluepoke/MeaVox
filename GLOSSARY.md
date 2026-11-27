# MeaVox — Domain Glossary

Ubiquitous language for the project. Decisions are recorded in `docs/adr/`. The UI speaks English and German (ADR 21): the English UI uses these terms (calling a Track a "song"), the German one the terms in the second column.

| Term | UI (Deutsch) | Meaning |
|---|---|---|
| **Source** | Quelle | A pluggable provider of songs: *search → candidates → resolve to stream/file*. One Source per service: each service behind the NewPipe adapter (YouTube, SoundCloud, …) is its own Source. Its name is the namespace of Track identities (`youtube:<id>`). See ADR 1, ADR 10. |
| **Local File Source** | lokale Datei | Files the user picks or shares into the app. Not searched: the file is *imported* (copied as Original Audio), and its identity is `local:<SHA-256 of the content>`, so the same content always maps to the same Track. |
| **Track** | Song | A song the user added, identified by Source + source ID. Owns Original Audio, stems per Separation Model, metadata, Lyrics, Takes and Mixes. See ADR 10. |
| **Track Pipeline** | Verarbeitung | Resumable processing states: Added → Downloading → Downloaded → Separating(model) → Ready / Failed. A Vocal-Free Track skips Separating. Deleting the stems moves Ready to Stems Deleted. While a Ready Track is re-separated, and after that failed, the Instrumental it was Ready with plays. See ADR 22. |
| **Stems Deleted** | Stems gelöscht | The Track Pipeline state of a Track whose stems the user deleted to free space. Opening the Track separates it again with the same Separation Model; the Original Audio plays meanwhile. See ADR 17. |
| **Ready** | Bereit | The Track Pipeline state in which every step the Track needs is done: stems exist, or the Track is Vocal-Free. See ADR 14. |
| **Original Audio** | Original | A Track's full audio as obtained, including the original vocals: downloaded from a Source (e.g. a music video's audio stream) or an imported local file (audio or video), kept as is. |
| **Separation** | Gesangsentfernung | ML source separation of Original Audio into an Instrumental and a Vocal Stem. See ADR 2. |
| **Separation Model** | Modell (Schnell, Mittel, Beste) | A selectable on-device ML model used for Separation; the user trades speed vs. quality, and models differ in whether they remove backing vocals. Tiers: Fast (Spleeter 2-stems), Medium (UVR MDX-Net Karaoke), Best (UVR MDX-Net Inst HQ 4). See ADR 3, ADR 15, ADR 19. |
| **Default Separation Model** | Standard für die Gesangsentfernung | The Separation Model chosen in Settings (initially Fast). It is pre-selected wherever a model is picked, including the import dialogs, where it can be changed for that Track before importing. Changing it never re-separates existing Tracks. See ADR 19. |
| **Instrumental** | Instrumental | Original Audio with the lead vocal removed (and, depending on the Separation Model, backing vocals); the default playback layer in a Session. |
| **Vocal Stem** | Originalgesang | The original vocals Separation removed from the Instrumental: the lead vocal, plus backing vocals if the Separation Model removes them. Serves as the Guide Vocal. |
| **Vocal-Free** | ohne Gesang | A Track whose Original Audio has no lead vocal (e.g. a karaoke video's audio), so it skips Separation and has no Instrumental or Vocal Stem. Set by the user. See ADR 14. _Avoid_: karaoke track, instrumental track. |
| **Backing Audio** | Playback | Whatever plays under the singer in a Session: the Instrumental or the Original Audio, as chosen per Track (Instrumental by default). The Original Audio until the Track has an Instrumental, and always on a Vocal-Free Track. See ADR 20. |
| **Take** | Take | One microphone recording of the user singing over a Track, at a fixed Transpose. A Track has many Takes. Its audio and timing are never modified after recording; only its name can change. |
| **Session** | — (not named in the UI) | Playing and singing over a Track on its screen: Backing Audio plays, Lyrics display, optional Monitoring, Practice Loop or recording of a Take, then Review. Opening a Track opens its Session once the Original Audio is stored. See ADR 9, ADR 20. |
| **Start Position** | Startposition | Where on the Track timeline a Take begins; Takes may cover only part of the song. |
| **Practice Loop** | Übungsschleife | A–B section looped without recording, for learning a passage. Only free playback loops; a Take recorded meanwhile runs straight on. Not kept after the Session. See ADR 9. |
| **Guide Vocal** | Leitstimme | The Vocal Stem blended into the headphones (Session) or into a Mix at low level. Never recorded; silent while recording without headphones, and in Review. In a Mix it is part of the Backing Audio: blended into the Instrumental before Transpose, at a level from 0 (none) to 1 (as in the Original Audio). Only over an Instrumental. See ADR 7, ADR 9. |
| **Review** | Anhören | Post-recording playback of a Take with Keep / Discard / Record again. |
| **Review Balance** | Playback beim Anhören | The level of the Backing Audio under a Take while it plays back (Review and kept Takes), lower than the Take by default so the voice can be judged. Only for listening; a new Mix starts at Match Level. _Avoid_: review volume, playback balance. |
| **Monitoring** | Mithören | Optional, dry, real-time software routing of the mic to **wired/USB** headphones during a Session, off at the start of every Session and after a route change. Never over Bluetooth. While it is on, the Session output takes the low-latency path. Warns when the round trip is above 40 ms. See ADR 4, ADR 13, ADR 16, ADR 23. |
| **Hardware Monitoring** | Hardware-Mithören | Zero-latency monitoring done by the audio interface itself (e.g. a USB mic's headphone jack), outside the app. Preferred over Monitoring when available. |
| **Audio Engine** | — (not named in the UI) | Native (C++/Oboe) component that owns the Session timeline: playback, Transpose, capture, Monitoring. |
| **Latency Correction** | Latenz-Korrektur | How much later than the platform reports the microphone is against the playback, per output route and output path, set by ear in Review ("Timing") and kept with each Take recorded there. It replaced the planned Latency Calibration. See ADR 23. |
| **Transpose** | Transponieren | Pitch-shift of Backing Audio (and guide Vocal Stem) by −6…+6 semitones, tempo unchanged. A property of each Take. See ADR 5. |
| **Last Transpose** | — (not named in the UI) | The Transpose a Track's control was last set to in a Session, whether or not a Take was kept. A new Session for the Track starts at it. It never changes existing Takes. _Avoid_: default transpose, saved key. |
| **Lyrics** | Songtext | Text of the song, from a LyricsProvider (LRCLIB first) or user edits; *plain* or *line-synced*. See ADR 6. |
| **Lyrics Offset** | Textversatz | Per-Track time shift between lyric timestamps (studio timing) and the Track's audio. |
| **Tap-to-Sync** | Mittippen | Editor in which the user taps per line during playback to turn plain Lyrics into synced Lyrics. |
| **Page View** | Seitenansicht | Lyrics display as static pages/scrolling text; works with plain Lyrics. |
| **Karaoke View** | Karaoke-Ansicht | Time-synced display: the current line is highlighted and word progress is interpolated within the line. Requires synced Lyrics. |
| **Mix** | Mix | Non-destructive combination of one Backing Audio + one Take + Mix Settings, spanning the Take (and a reverb's tail). Heard in a Mix Preview; rendered only for Export. See ADR 7. |
| **Mix Settings** | Mix-Einstellungen | Backing level, vocal level, Vocal Nudge, guide-vocal level, Vocal Effects. A new Mix starts at Match Level: the backing at 0 dB, the vocal level matched. |
| **Match Level** | Pegel angleichen | The Backing Audio at its own level (0 dB) and the vocal level set so the dry Take is as loud as the Backing Audio over the Take's span, by integrated loudness (ITU-R BS.1770, LUFS). Every new Mix starts so; in the Mix Editor it can be done again, e.g. after switching the Backing Audio. A Take with no measurable loudness gets 0 dB. See ADR 7. _Avoid_: normalise, auto gain. |
| **Vocal Nudge** | Gesang verschieben | Manual ±200 ms alignment of a Take in its Mix, applied on top of the Latency Correction. |
| **Vocal Effects** | Gesangseffekte | Mix-time presets for the Take: a reverb (Dry, Room or Hall), gentle compression and a high-pass filter. Heard in the Mix Preview and rendered into an Export; never applied to Monitoring or Review. A reverb makes the Mix run on for its tail. See ADR 7. |
| **Mix Editor** | Mix bearbeiten | Where one Mix's Backing Audio and Mix Settings are changed, heard in a Mix Preview and exported. _Avoid_: mixer. |
| **Mix Preview** | Vorschau | A Mix played in the Session as it will be exported, from its Take's Start Position until the Take ends (and a reverb's tail has rung out); changes to its Mix Settings are heard at once. _Avoid_: mix playback. |
| **Offline Render** | — (not named in the UI) | The Audio Engine turning a Mix (or, for a Backing Export, the Backing Audio alone) into audio faster than real time, with the same processing as Review and the Mix Preview, so an Export sounds like them. See ADR 7. _Avoid_: bounce, mixdown. |
| **Export** | Exportieren | Rendering a Mix, or a Track's Backing Audio alone (Backing Export), to WAV, MP3 or M4A/AAC in `Music/MeaVox/` or via share sheet. See ADR 8. |
| **Backing Export** | Playback-Export | An Export of a Track's Backing Audio (Instrumental or Original Audio) alone at a chosen Transpose, with no Take, e.g. as a backing track for a live performance. _Avoid_: karaoke track, instrumental export. |
| **Cover Art** | Cover | A Track's picture, shown in the Library and carried by its MP3 and M4A Exports: the Source's largest picture of the item, downloaded with the Original Audio, or the picture an imported file embeds. Stored with the Track's files as a square JPEG (centre-cropped, #50). A network Track added before #42 gets its Source's picture later, once; a Track without one is exported without. See ADR 8. _Avoid_: thumbnail (the small picture in a list row). |

## Open questions
- None. License → ADR 11, platform baseline → ADR 12.
