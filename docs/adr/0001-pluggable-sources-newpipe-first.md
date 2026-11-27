# 1. Pluggable Sources, NewPipeExtractor first, sideloaded distribution

- Status: Accepted, amended by [ADR 11](0011-license-gpl-3.md) (GPL-3.0-or-later) and [ADR 24](0024-two-apps-meavox-and-meavox-max.md) (two apps)
- Date: 2026-11-14

## Context
MeaVox has to find music videos on several platforms and get their audio out. Platform terms (YouTube in particular) forbid downloading streams, and the Play Store rejects apps that do it. Extractor libraries break whenever a platform changes something.

## Decision
- Define a `Source` abstraction from the start: *search → candidates → resolve to a stream/file*.
- First implementation: **NewPipeExtractor** (YouTube, SoundCloud, Bandcamp, PeerTube, …).
- A **local-file Source** (file picker / Android "Share to") is part of the core.
- Distribution starts as **sideload / F-Droid / GitHub releases**. Network extractors live in their own build flavor/module, so a Play-Store-safe flavor (local Sources only) stays possible later.

## Consequences
- NewPipeExtractor is **GPL-3.0**. Any APK that bundles it must be distributed under GPL-3.0. The repo is MIT today. Options: keep the core MIT and license the extractor module and that flavor's APK as GPL-3.0, or relicense the whole project as GPL-3.0. *(To be decided.)*
- Extractor breakage is expected; the extractor must be updatable without touching the rest of the app.
- The user is responsible for complying with platform terms when using network Sources.

## Note: two apps (#21, 2026-11-27)
The Play-Store-safe flavor this ADR kept possible is now an app of its own: **MeaVox** (`local` flavor, Local File Source only)
goes to Google Play, and **MeaVox Max** (`network` flavor, with the NewPipe Sources) is distributed through F-Droid and GitHub
Releases, as decided here. See ADR 24.
