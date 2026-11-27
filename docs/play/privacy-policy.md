# Privacy policy for MeaVox

_Last updated: 27 November 2026 (cover search)_

MeaVox is a karaoke and singing-practice app for Android, developed as free software under the GNU GPL,
version 3 or later (<https://github.com/bluepoke/MeaVox>). This policy covers the MeaVox app
(`eu.schicht8.meavox`).

## In short

MeaVox has no account, no ads, no analytics and no tracking. The developer collects no data about
you. Your songs, recordings and settings stay on your device. The app connects to the internet only
to look up lyrics, to search for cover art when you ask it to, and to download the vocal removal
models.

## What stays on your device

- **Songs you import**: audio or video files you pick or share into the app are copied into the
  app's private storage, with their vocal-removed versions.
- **Your recordings (Takes)**: when you record, the app uses the microphone to record your voice. It
  asks for the microphone permission the first time you record. Recordings are stored only in the
  app's private storage and are never uploaded.
- **Exports**: songs you export are saved in your device's `Music/MeaVox` folder, or handed to the app
  you choose in Android's share sheet. Nothing is sent anywhere unless you share it.
- **Settings**: such as your name, which the app writes into the artist tag of your exports.

Uninstalling the app deletes everything in its private storage. Exports in `Music/MeaVox` stay until
you delete them.

## What is sent over the internet

- **Lyrics**: to find the lyrics of a song, the app sends its title and artist (as the app knows
  them, or as you type them in a lyrics search) to LRCLIB (<https://lrclib.net>), a free lyrics
  service. Requests name the app and its version. No other information about you or your device is
  sent. LRCLIB's own terms and privacy practices apply to these requests.
- **Cover art search**: when you search for a song's cover, the app sends the title and artist
  (prefilled from the song, as you may edit them) to MusicBrainz (<https://musicbrainz.org>), an open
  music database, and loads the covers it finds from the Cover Art Archive (<https://coverartarchive.org>,
  hosted by the Internet Archive). Requests to MusicBrainz name the app and its version. Nothing is sent
  unless you open the cover search. Their own terms and privacy practices apply to these requests.
- **Vocal removal models**: the first time a model is used, the app downloads its file from GitHub
  (<https://github.com>): the Fast model from the MeaVox project's releases, the Medium and Best models
  from the Ultimate Vocal Remover model repository. These are plain file downloads; GitHub's privacy
  statement applies to them.

All connections use HTTPS. The app sends no device identifiers, no location and no usage data.

## Permissions

- **Microphone** (`RECORD_AUDIO`): to record your singing. Asked for when you first record.
- **Internet** (`INTERNET`): for the lyrics, the cover search and the model downloads above.
- **Notifications** (`POST_NOTIFICATIONS`) and **foreground services** (`FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`): to show the progress of
  vocal removal while it runs in the background.
- **Network state, wake lock and start after reboot** (`ACCESS_NETWORK_STATE`, `WAKE_LOCK`,
  `RECEIVE_BOOT_COMPLETED`): added by Android's WorkManager library, which runs vocal removal in the
  background, so it can wait for a connection, keep the device awake while it works and continue after
  a restart.

## Children

MeaVox is not directed at children and collects no data from anyone.

## Changes and contact

Changes to this policy are published in this file, in the project's repository
(<https://github.com/bluepoke/MeaVox/blob/main/docs/play/privacy-policy.md>), with their date. Questions
can be asked in the project's issue tracker: <https://github.com/bluepoke/MeaVox/issues>.
