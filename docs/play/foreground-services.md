# Foreground service declarations for MeaVox

For the Play Console (App content → Foreground service permissions). MeaVox runs its long steps as
WorkManager jobs in a foreground service with a progress notification (ADR 10, ADR 15). The manifest
declares the service `androidx.work.impl.foreground.SystemForegroundService` with
`android:foregroundServiceType="dataSync|mediaProcessing"`.

## `FOREGROUND_SERVICE_MEDIA_PROCESSING` (Android 15 and later)

- **Task**: vocal removal (Separation). An on-device machine-learning model separates a song the user
  imported into an instrumental and a vocal track, which the user then sings over. On a typical phone this
  takes one to fifteen minutes per song, depending on the model and the song's length.
- **Started by**: the user, when they import a song or choose to remove its vocals again with another
  model.
- **Why it can't wait or be stopped**: the user is waiting to sing the song. Stopping it when the app
  leaves the screen would lose minutes of work and leave the song without an instrumental. The job keeps
  checkpoints and continues where it stopped after process death.
- **What the user sees**: an ongoing notification with the song's title and the progress, and the same
  progress in the app.
- **Video showing the task**: record the import of a song and the progress notification while the app is in
  the background.

## `FOREGROUND_SERVICE_DATA_SYNC`

- **Task**: the same vocal removal on Android 14 and earlier, where the `mediaProcessing` type doesn't
  exist yet; and downloading a vocal removal model (30 to 75 MB) the first time it is used, as the first
  part of that job.
- **Started by**: the user, as above. A model is downloaded only after the user agreed to its download
  size.
- **Why it can't wait**: the vocal removal needs the model; the download is part of the job the user
  started and continues after interruptions.
- **What the user sees**: the same progress notification, saying that the model is being downloaded.
