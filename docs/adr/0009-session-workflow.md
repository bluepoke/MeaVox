# 9. Session workflow: start anywhere, countdown, practice loop, guide vocal, review

- Status: Accepted
- Date: 2026-11-15

## Decision
- **Start anywhere**: a Take stores its **Start Position** on the Track timeline. Partial Takes are valid. There is no punch-in into an existing Take.
- **Count-in**: an optional **time-based** countdown (visual, with an optional click). No beat detection in v1.
- **Practice Loop**: an A–B section loops *without recording*. Monitoring and Transpose stay available.
- **Guide Vocal**: the Vocal Stem can be blended into the headphone mix during a Session. It is never recorded.
- **Headphones** are strongly recommended for recording. Without them the app warns but does not block.
- **Input level meter** and clipping warning while recording, plus one **input gain** setting per device.
- **Review** after stopping: immediate playback of the Take against Backing Audio, with **Keep / Discard / Record again**. *Keep* creates the Take and a default Mix.

## Deferred
- Live pitch display, intonation curve, scoring.
- Beat-accurate count-in (needs tempo detection).

## Note: the Practice Loop and the Guide Vocal built in #18 (2026-11-23)
- **Practice Loop**: A and B are marked at the current position while the Session is Idle; B must come at least 1 s after A, and marking B starts playback over from A. Only free playback loops: a Take recorded while a loop is set runs straight on, Review and kept Takes too, and playback started past B plays on. The loop stays set after a Take and ends with the Session; it is not stored.
- The loop is kept in ms, so it stays at its times when the Backing Audio switches to a file at another rate.
- The Audio Engine wraps inside its callback, through the same BackingRenderer as all playback: from B on it reads the frames from A again, so the stretcher's read-ahead wraps with it and a transposed loop is as seamless as a plain one. The last 10 ms before B fade over to the 10 ms before A, so going on at A doesn't click.
- **Guide Vocal**: a level from 0 to 100 %, like a Mix's (ADR 7, note on #16), that starts at 0 in every Session and is not stored. It is the Vocal Stem of the Instrumental that plays, added before the stretcher, so it follows Transpose. It can't be heard over the Original Audio or on a Vocal-Free Track (ADR 14); the control says why. The engine loads the Vocal Stem the first time the level is raised while the Session is Idle (decoding it holds the Session up, so a level raised during a Take comes in after it), keeps it at level 0, and drops it with the Instrumental. Loading or dropping it doesn't interrupt playback.
- It plays in free playback, in the Practice Loop and while recording over headphones (wired, USB, Bluetooth). **Recording over anything else silences it** (the speaker, an output the app doesn't know), so the microphone can't pick it up; it is never mixed into the Take, which is the microphone alone. Review, kept Takes and a new Take's default Mix have no Guide Vocal; a Mix Preview plays its own Mix's.
