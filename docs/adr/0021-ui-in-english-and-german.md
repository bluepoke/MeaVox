# 21. The UI in English and German, with the domain core free of display text

- Status: Accepted
- Date: 2026-11-23

## Context
No text the UI showed was translatable: there was no `strings.xml`, about 270 texts sat in the Compose screens and the workers, and some came from below the UI. The domain core threw reasons as English sentences, `Failed` Track Pipeline states and Lyrics kept their reason as English text in Room, and adapters passed exception messages through as reasons.

## Decision
- **English and German** (#43), as Android string resources: `values/` is English, the default and the fallback for any other language; `values-de/` German.
- **The language follows the device.** Android 10–12 use the first of the device's preferred languages the app has. Android 13+ also offer the system's per-app language setting, from a generated locale config. There is **no language picker inside the app**, so no AppCompat.
- **The domain core carries no display text.** Why a step failed is a `FailureReason`: a `FailureKind` the UI words, the service involved ("YouTube", "LRCLIB"), and a detail shown as it is (an HTTP status, a library's own message). An error nobody foresaw gets a general, translated sentence, with its own message after it. Mixes and Backing Exports that can't be made say why with an enum. View models hold `UiText` (a string resource with its arguments, or verbatim text) and the UI resolves it, so a language change words it again.
- **Stored reasons** are a stable code, the service, and the detail (Room version 14). A reason stored before as English text reads as `Unexpected` with that text, and is still shown as it was.
- **Names the app creates stay English** in any UI language: "Mix 1", "Take 1", "Untitled", and the Backing Export title suffix "(Instrumental, +2)" / "(Original, −1)". They end up in file names and tags, which shouldn't depend on the language the UI happened to be in.
- **The German terms** for the domain terms are a column of `GLOSSARY.md`. Take and Mix stay English, as musicians say them; Backing Audio is "Playback".
- **Lint guards it**: `MissingTranslation` is an error, and the project's own `HardcodedUiText` check (`:lint-checks`) reports a string literal with letters, also in an `if` branch, passed to a `@Composable`, a Toast, a snackbar, a notification, a notification channel's name or a share chooser. It doesn't follow values through variables or helpers, so a text built elsewhere has to be checked in review. CI runs Lint. A unit test checks that German has every key English has, and the same placeholders.

## Consequences
- A new screen text goes in both `strings.xml` files; Lint and the translation test fail the build otherwise. Format names that are the same in every language (MP3, VBR V2) are resources with `translatable="false"`.
- Units and symbols (dB, ms, −, ·) are not translated.
- The model tiers' names come from string resources by catalogue ID; a model the app doesn't know keeps its catalogue name.
- Text that comes into a reason as a detail (a server's or a library's message) stays in the language it came in.
