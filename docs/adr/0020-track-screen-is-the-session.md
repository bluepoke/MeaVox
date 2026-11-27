# 20. One screen per Track: Track detail and the Session merged, with a per-Track Backing Audio choice

- Status: Accepted (amends ADR 10, ADR 14, ADR 17)
- Date: 2026-11-18

## Context
Track detail played the Track and showed the Track Pipeline status, the Lyrics choice and the Vocal-Free toggle. Its "Sing" button opened a separate Session screen that played the same audio again and added the lyrics, the transport, Transpose, the microphone and Takes. The extra step had no use, and the two screens had their own playback paths that both had to follow Separation and Vocal-Free. What played was never the user's choice: the Instrumental once it existed, else the Original Audio.

## Decision
- **One screen per Track** (#34). Opening a Track from the Library, a search, an import or a share opens it; there is no separate Session screen or Sing button. The Lyrics fill the top half. The lower half shows the Track Pipeline status, the Lyrics status, the Backing Audio choice, the Session's controls and Takes, and the Vocal-Free toggle.
- **The Session opens with the screen** as soon as the Original Audio is stored (ADR 10). Until then the screen shows the download progress and the Lyrics. One Track at a time: opening a Session closes the one before.
- **Backing Audio choice**: Instrumental or Original Audio, **kept per Track**, Instrumental by default.
  - The Instrumental plays only once the Track is Ready with stems. Before that it can't be chosen and the Original Audio plays. When Separation finishes, the Session switches only if the choice is the Instrumental.
  - A change is heard at once during free playback, at the same position (stems are sample-aligned). While a Take is recorded, reviewed or played back, it waits until the Session is Idle again.
  - A Vocal-Free Track has no Instrumental; the choice is hidden and it plays its Original Audio (ADR 14).
- **"Last sung"** in the Library is when a Take last started recording, not when the Track was opened. Opening a Track to listen is not singing.

## Considered Options
- **Keep two screens, add the Backing Audio choice to the Session.** Rejected: the Track detail step stays useless, and its own playback path keeps duplicating the Session's switching rules.
- **The choice per Session only, not kept.** Rejected: someone who sings a song over its Original Audio (e.g. a duet they like to hear) would have to switch every time.
- **"Last sung" when the screen opens.** Rejected: with one screen, every visit to a Track would count as singing it.

## Consequences
- ADR 10 and ADR 17 name "Track detail or a Session" as places to open a Track; there is only the Track's screen now. Opening it still brings deleted stems back (ADR 17).
- ADR 14's Vocal-Free toggle and the "Mark Vocal-Free" snackbar after a share are on the Track's screen.
- The Track's screen plays through the Session's output path, which since ADR 16 is the normal one that Track detail used.
- The Backing Export (#24), the Guide Vocal (#18) and Monitoring (#9) put their controls on this screen when they are built.

## Note: the screen stays on while the Session runs (#48, 2026-11-27)
The Track's screen keeps the display on while something runs in the Session: audio plays (free playback, recording, Review, a kept Take, the Mix Preview) or the silent countdown before a Take counts. Paused or stopped, and on every other screen, the system screen timeout applies. It is the window flag only (`View.keepScreenOn`), with no wake lock and no permission; `Session.running` decides it.
