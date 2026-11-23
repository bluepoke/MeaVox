# 14. Vocal-Free Tracks skip Separation

- Status: Accepted (amends ADR 2, ADR 7, ADR 10; amended by ADR 20)
- Date: 2026-11-16

## Context
Some Original Audio has no lead vocal to remove, for example the audio of a karaoke video. Running Separation on it costs minutes of CPU and battery, and the model can damage the result (strip backing vocals, add artifacts). ADR 2 and ADR 10 made Separation a step every Track goes through, so there was no way to skip it.

## Decision
- A Track can be marked **Vocal-Free**. A Vocal-Free Track **skips Separation**: it has no Instrumental and no Vocal Stem, and its Backing Audio is always the Original Audio. The Instrumental / Original Audio choice is hidden.
- The Track Pipeline goes `Downloaded → Ready` without `Separating`. **`Ready` means every step the Track needs is done**, not that stems exist. The Session does not switch to an Instrumental.
- The user marks a Track Vocal-Free with a secondary option when adding it (search result and local import; the default add stays one action) or with a toggle on Track detail at any time. Turning it on cancels a running Separation; a Track whose Separation `Failed` moves to `Ready`.
- Re-adding an existing Track with the option set opens it **unchanged**. Only the Track detail toggle changes an existing Track, so deleting stems is never hidden inside an add flow.
- Turning it on **after Separation** deletes the stems after a confirmation that names the space freed. Mixes that used the Instrumental switch to the Original Audio, which keeps them aligned because stems are sample-aligned with the Original Audio (ADR 8).
- Turning it off makes the Track behave like a newly downloaded one: `Ready → Separating(default model) → Ready`, then the Backing Audio switches to the Instrumental. Existing Mixes keep the Original Audio until the user switches them.
- The **Guide Vocal** (Session and Mix) is disabled on a Vocal-Free Track, with a short reason. Re-separation is unavailable, and the storage screen shows no stems and offers no regeneration.
- A Backing Export of a Vocal-Free Track is tagged **"Title (Instrumental, ±n)"**: the tag says what the file is, because "Original" means *with the artist's vocals* on other Tracks.
- **No automatic detection** in v1: the user always decides.

## Note: built in #25 (2026-11-17)
- A Vocal-Free Track is `Ready` **without a Separation Model**, so nothing that plays a Track needs the flag to know it has no stems.
- **Shares** (a link or a file shared from another app) add at once, as before. Track detail then offers "Mark Vocal-Free" in a snackbar while Separation hasn't finished. Picking a local file has a secondary "Import file without vocals" item in the Library's menu, and a search result has a secondary "Add without vocal removal" button.
- Marking, and every state a Separation job writes, are **atomic changes of the Track**. A job that hasn't stopped yet when the Track is marked can't write `Separating` or `Ready` over it.
- Deleted stems (ADR 17) don't come back for a Vocal-Free Track: marking a `Stems Deleted` Track makes it `Ready` at once.
- Mixes (#14), the Backing Audio choice (#17) and the Guide Vocal (#18) don't exist yet. Their parts of this decision land with those tickets.
- Since #14, marking a Track switches its Mixes on the Instrumental to the Original Audio; unmarking leaves them there, and the Mix Editor can switch each back once the Track has an Instrumental. The editor hides the Backing Audio choice on a Vocal-Free Track. Since #16 the Mix Editor disables the Guide Vocal level on a Vocal-Free Track, with the reason that it has no vocals to blend in (ADR 7, note on #16).
- Since ADR 20 (#34), Track detail and the Session are one screen per Track: the toggle and the snackbar are there, and the Backing Audio choice, built in #34, is hidden on a Vocal-Free Track.

## Note: one import sheet (#17, 2026-11-18)
- ADR 19's import sheet replaces the secondary add options above. A search result, a picked file, and a link or file shared from another app all show one sheet with a "No vocals" checkbox and the Separation Model selection, hidden while the checkbox is set.
- Shares no longer add at once, so the post-share "Mark Vocal-Free" snackbar, the "Import file without vocals" menu item and the "Add without vocal removal" button are gone.
- A shared link or a search result for a Track already in the Library opens it without the sheet (search results since #35).
- Unmarking the toggle opens the model picker with the Default Separation Model pre-selected.

## Considered Options
- **The Original Audio counts as the Instrumental** (a copy or a reference, with no Vocal Stem). It keeps "Instrumental by default", but the Backing Audio choice would offer the same audio twice, and Mixes would reference an Instrumental "by Separation Model" that no model produced. Rejected.
- **Detecting vocal-free audio.** A title hint ("karaoke", "instrumental") is unreliable: karaoke versions with guide vocals and lyric videos contain vocals, and many karaoke uploads don't say so. Audio analysis would need Separation, which costs exactly what this decision saves. Deferred.

## Consequences
- The Track Pipeline state stays the single source of truth for "can I sing in final form" (ADR 10). The UI does not read the Vocal-Free flag to decide whether a Track is finished.
- Takes stay valid in both directions of the toggle, because they align with the Original Audio and its stems alike.
