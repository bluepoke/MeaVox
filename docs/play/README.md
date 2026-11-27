# MeaVox on Google Play: what the Play Console asks for

Ready to paste when MeaVox (the `local` flavor, `eu.schicht8.meavox`, ADR 24) is submitted (#45).
Everything here is about **MeaVox** only; it never mentions the other app (#21).

| Play Console page | Source |
|---|---|
| Store listing (title, short and full description, icon) | [`app/src/local/fastlane/metadata/android/`](../../app/src/local/fastlane/metadata/android) (fastlane `supply` format, en-US and de-DE) |
| Privacy policy (URL) | [`privacy-policy.md`](privacy-policy.md): link its GitHub page |
| Data safety | [`data-safety.md`](data-safety.md) |
| Foreground service declarations (App content → Foreground service permissions) | [`foreground-services.md`](foreground-services.md) |
| Content rating questionnaire | [`content-rating.md`](content-rating.md) |
| Phone and tablet screenshots, feature graphic (1024 × 500) | not in the repository yet: to be taken for #45 and added to `app/src/local/fastlane/metadata/android/<locale>/images/` |
| App bundle | `./gradlew bundleLocalRelease` (signed with the upload key from the environment, see the README) |

The store listing is not in the repository's top-level `fastlane/metadata/android/`: F-Droid reads that one for every app built
from this repository, so it holds the other app's listing (ADR 24).
