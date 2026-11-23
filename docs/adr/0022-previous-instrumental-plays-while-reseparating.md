# 22. The previous Instrumental plays while a Track is re-separated

- Status: Accepted (amends ADR 10, ADR 17, ADR 19)
- Date: 2026-11-23

## Context
Re-separating a Ready Track with another Separation Model (#17, ADR 19) moved it to `Separating(new model)` at once, so the Session played the Original Audio for the whole run, about 14 min for a 4-minute song with Best on the weak device. If the run failed, the Track was `Failed`, and the stems it had been Ready with sat on disk with no way to play them. Yet they are complete and sample-aligned with the Original Audio, like every Instrumental (ADR 8).

## Decision
- While a Track that was Ready with model A is re-separated with model B, **A's Instrumental keeps playing** (if the Instrumental is the Track's Backing Audio choice). The Track Pipeline remembers A as the **previous** model: `Separating(B, progress, previous = A)`, persisted in Room (version 16, `pipeline_previous_model`).
- **When B finishes**, the Track is `Ready(B)`, and the Session switches to B's Instrumental as it does when any Separation finishes: never under a Take being recorded, reviewed or played back (ADR 10, ADR 20).
- **When B fails**, the Track is `Failed(Separating, reason, previous = A)`. A's Instrumental plays on, and the failure is shown with Retry, which re-separates with B and keeps A playing meanwhile. A `Ready(A)` that also remembers B's failure was considered and rejected: a Track that is Ready wouldn't show in the Library that its vocal removal failed, and Ready would no longer mean "every step the Track needs is done" (ADR 14).
- **Re-separating back to A** while B runs, or after B failed, needs no Separation: A's stems are complete, so the Track is `Ready(A)` at once, and a running B job is stopped. Its checkpoint stays (#28), so separating with B later continues where it stopped.
- **Re-separating with a third model C** while B runs replaces B (ADR 10, #28) and keeps A playing: `Separating(C, previous = A)`.
- **Nothing to keep playing**: a Track whose first Separation is replaced, whose Separation failed without stems, or whose stems were deleted (ADR 17), plays the Original Audio while it is separated, as before.
- **Marking the Track Vocal-Free** meanwhile still makes it `Ready` without stems and deletes every model's stems (ADR 14).
- **Storage screen**: the stems that play while the Track is re-separated, or after that failed, can't be deleted, like stems being made. They show as "in use". Once the Track is Ready again they can be deleted as before (ADR 17).
- **Track screen**: under the vocal removal progress it says "Until then, the Instrumental by Fast is used.", and under a failure "The Instrumental by Fast is still used." After a failure, the vocal removal row names the model Retry separates with. The re-separate dialog says the current Instrumental plays until the new one is ready, or, without one, the Original Audio, and that picking the model it plays goes back to it at once.

## Consequences
- The Track's Instrumental is `Ready.model`, else the previous model of `Separating` or `Failed`; the Session, Mixes, the Guide Vocal and Backing Exports use it unchanged.
- Takes stay aligned throughout, because every Instrumental is sample-aligned with the Original Audio (ADR 8).
- The previous model's stems stay on disk after B is Ready, until they are deleted on the storage screen (as since #17). Mixes on A keep A while they are stored (ADR 7).
