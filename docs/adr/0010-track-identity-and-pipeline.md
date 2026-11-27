# 10. Track identity and processing pipeline

- Status: Accepted (amended by ADR 14, ADR 17, ADR 20, ADR 22)
- Date: 2026-11-15

## Decision
- A Track is identified by **Source + source ID** (e.g. `youtube:<videoId>`, `local:<content hash>`). Re-adding the same item opens the existing Track.
- **Track Pipeline** states: `Added → Downloading → Downloaded → Separating(model) → Ready`, plus `Failed(step, reason)`. Lyrics are fetched in parallel and never block.
- A Session can start once the Track is **Downloaded** (on Original Audio). It switches to the Instrumental when Separation finishes, if that is the Track's Backing Audio choice (ADR 20).
- Each step is a **resumable WorkManager job**: it survives process death, retries network errors with backoff, and shows a progress notification.

## Note: Vocal-Free Tracks (ADR 14)
A Vocal-Free Track skips Separation and goes `Downloaded → Ready`. `Ready` means every step the Track needs is done, not that stems exist.

## Note: deleted stems (ADR 17)
Deleting a Ready Track's stems on the storage screen moves it to **Stems Deleted**, which the Track Pipeline doesn't queue. Opening the Track (its screen, which is also its Session since ADR 20) moves it back to `Separating` with the same model.

## Note: local import (#5)
Importing a picked or shared file (Local File Source) is **not** a WorkManager job. The read grant for a picked or shared content URI does not survive the process, so a job retried after process death could not read the file. The import runs in one short in-process step instead: copy and hash, then the Track moves `Added → Downloaded`. Staging files of imports that never finished are deleted at the next start. An import that died after the Track row was written but before the file was stored leaves a Track in `Added`; importing the same file again completes it.

## Note: Separation jobs per model (#28)
A Separation job is keyed by Track **and** Separation Model (`separate:<track>:<model>`). Jobs for the same Track and model queue behind each other. Re-separating a Track with another model while one runs **replaces** the running Separation instead of queuing behind it: the Track moves to `Separating(new model)` first, so the old job writes nothing more, then the old job is cancelled and the new one queued. A Separation starts only once any other Separation of the same Track has stopped, so the new job waits if the old one is slow to stop after its cancellation. The old model's checkpoint stays, so separating with it again continues where it stopped. Queuing behind was rejected: the Track has one `Separating(model)` state, the user would wait for a model they no longer want, and two Separations at once risk running out of memory (Best and Medium need about 1 GB). Jobs queued before #28 (`separate:<track>`) are stopped at app start, and the Track Pipeline queues their Tracks again under the new name; the same wait keeps an old job and its replacement from writing the same checkpoint at once.

## Note: the previous Instrumental while re-separating (#37)
A Track re-separated after it was Ready keeps its previous model while the new one runs, `Separating(model, previous)`, and after the new one failed, `Failed(step, reason, previous)`. That model's Instrumental plays meanwhile. Re-separating back to it makes the Track Ready at once, and the running job is stopped as above. See ADR 22.

## Note: notification permission (#27)
On Android 13+ the progress notification needs the `POST_NOTIFICATIONS` permission. MeaVox asks for it once, when a Track is first `Separating` (with a short explanation first), not at app start. If the user declines, the steps still run as foreground jobs, but their notification stays hidden until notifications are allowed in the system settings; the Library and the Track's screen show the progress either way. The app doesn't ask again.

## Note: a Track's title and artist when it is added (#52, 2026-11-27)
The import sheet (search result, shared link, picked or shared file, ADR 19) now also has editable **Title** and **Artist** fields, and the
Track is added with what they hold. They start with a suggestion: the Source's title and artist (e.g. a video's title and channel)
cleaned with `TitleCleaner`, or a file's own tags, or its cleaned file name (without a leading track number). Then the suggestion is
checked against MusicBrainz recordings (the client of #51, sharing its User-Agent and its one request a second): a recording replaces
it, in the fields the user hasn't edited and only while the title is untouched, only when it is clearly the same song (score of 90 or more, the same title and artist ignoring
case, accents and punctuation; without an artist, only when every match of that title has the same artist). Offline or without such a
match the cleaned suggestion stays, and adding never waits for MusicBrainz (only, for a file, for its tags). Adding with the suggestion stays one tap.

## Consequences
- Pipeline steps must be idempotent and keyed by Track ID (and Separation Model).
- The pipeline state is persisted in Room and is the single source of truth for the UI.
