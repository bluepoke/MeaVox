# 24. Two apps from one code base: MeaVox on Google Play, MeaVox Max on F-Droid and GitHub Releases

- Status: Accepted (amends ADR 1, ADR 8, ADR 15, ADR 19)
- Date: 2026-11-27

## Context
ADR 1 kept the network Sources in their own build flavor, so that a Play-Store-safe flavor would stay possible, and started
distribution outside the Play Store. Both flavors were built from the start (#4). Releasing them (#21) needs application IDs
that can never change afterwards, signing, store texts, a build F-Droid accepts, and a decision about the Separation Models
whose weights have no license.

## Decision
- **Two apps**, both GPL-3.0-or-later, from the same code:

  | App | Flavor | Application ID | Sources | Channels |
  |---|---|---|---|---|
  | **MeaVox** | `local` | `eu.schicht8.meavox` | Local File Source only | Google Play |
  | **MeaVox Max** | `network` | `eu.schicht8.meavox.max` | Local File Source and the NewPipe Sources | F-Droid and GitHub Releases |

  MeaVox has the plain ID because it is the app most people will find. Debug builds use the same IDs.
- **MeaVox never points at MeaVox Max** (app texts, licenses, Play listing), so Play doesn't read it as a way around its
  policies. A test of the `local` flavor (`NoMaxInMeaVoxTest`) keeps it so.
- **Signing comes from the environment** only: Gradle properties `meavox.signing.<flavor>.storeFile|storePassword|keyAlias|keyPassword`
  or the variables `MEAVOX_SIGNING_<FLAVOR>_STORE_FILE|STORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD`. Without them a release build
  is unsigned. MeaVox is uploaded as an App Bundle signed with an upload key (Play App Signing); MeaVox Max is signed with
  the project's release key, which the release workflow reads from the repository's secrets.
- **Tagging `v<versionName>`** builds, signs and publishes the MeaVox Max APK to GitHub Releases (`.github/workflows/release.yml`).
  `versionCode` and `versionName` stay in `app/build.gradle.kts`, where F-Droid reads them.
- **The build fetches nothing but Gradle's repositories**, as F-Droid requires: Signalsmith Stretch, Signalsmith Linear and
  libFLAC are Git submodules in `third_party/`, LAME 3.100 is vendored there unmodified, and CMake runs with
  `FETCHCONTENT_FULLY_DISCONNECTED`. The NDK is pinned (r28c).
- **Store listings**: F-Droid reads the repository's top-level `fastlane/metadata/android/` for every app built from it and
  doesn't look into `app/src/<flavor>/`. So the top-level directory holds **MeaVox Max**'s listing, and MeaVox's Play listing
  lives in `app/src/local/fastlane/metadata/android/` (the source for `fastlane supply`). The Play Console's other texts
  (privacy policy, Data safety, foreground services) are in `docs/play/`; the fdroiddata recipe draft is in `docs/fdroid/`.
- **Third-party licenses** are listed in the app (Settings → Third-party licenses), each app with what it contains, and the
  Separation Models' weights. The texts are in `docs/licenses/` (`docs/licenses/max/` for MeaVox Max only) and are copied
  into each app's assets at build time. LAME is linked dynamically (ADR 8); its source offer is the vendored source in the
  repository, named in the app and the README.
- **Separation Models in the release** (owner's decision, 2026-11-27): both apps offer **all three** models, Fast, Medium and
  Best, although the UVR authors haven't answered yet (UVR #2444). This keeps ADR 19's interim download for the release
  against its license gate and ADR 15's distribution rule: Medium and Best are still downloaded from the UVR model repository,
  pinned by SHA-256, and the licenses screen says they have no license. **Fast** is downloaded from the project's own release
  asset (`models-1`, with its MIT license), as ADR 15 asks.

## Considered Options
- **MeaVox Max with the plain ID, MeaVox with a suffix** (the flavors as they were): rejected; the Play app is the one most
  people will search for, and its ID shows in its Play URL.
- **The Play listing in the top-level `fastlane/`, as #21 first said**: rejected; F-Droid would show it for MeaVox Max.
- **Release builds with Fast only until UVR answers** (ADR 15, ADR 19, #21 as written): rejected by the project owner for the
  first release.
- **Vendoring every native dependency**: rejected; submodules keep the upstream history and are what F-Droid checks out.
  LAME is vendored because it has no Git upstream.

## Consequences
- An installed debug build of the network flavor under `eu.schicht8.meavox` (before #21) is now MeaVox's ID. Installing the new
  network build adds MeaVox Max next to it, with its own, empty storage.
- Until UVR #2444 is answered, MeaVox Max carries F-Droid's `NonFreeAssets` anti-feature (besides `NonFreeNet` for YouTube
  and similar services), and both apps depend on a third-party repository for Medium and Best. If the authors refuse, both
  models leave the catalogue (ADR 19).
- Clones need `git submodule update --init` (or `git clone --recursive`); CMake stops with that hint when a submodule is empty.
- The store accounts, keys, secrets and submissions are #45.
