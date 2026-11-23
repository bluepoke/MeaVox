# Hardware checklist

Manual checks on a real device for behaviour that unit tests can't cover (spec #1, testing decisions). Run them after changing the Audio Engine, and note device, Android version and build.

## Session recording (#8)

Prerequisites: a Track imported from a local file, wired headphones, and a way to make an acoustic loopback (earbud held against the microphone).

1. **Permission on first record.** Fresh install: open a Track, tap **Sing**. No permission prompt appears. Tap **Record**: the microphone prompt appears. Deny it: a message explains why it is needed and nothing records. Tap **Record** again and allow it: recording starts.
2. **Meter, clipping and gain.** With the microphone allowed, the meter moves when you speak. Shout close to the mic: the meter turns red and the clipping warning shows for about a second. Lower the input gain and check that the meter drops. Plug in a USB microphone: its gain starts at its own value (0 dB the first time). Unplug it: the built-in mic gets its previous gain back. After restarting the app, both values are still there.
3. **Start Position.** Seek to 1:00, record 10 s, stop. Review starts playing at 1:00 and shows "Take from 1:00, 0:10 long". Keep it: the Take is listed as "From 1:00".
4. **Countdown.** Turn the countdown on and tap Record: it shows 3, 2, 1, and recording starts with playback at the Start Position. Tap Cancel during the countdown: nothing records. The setting is still on after leaving and reopening the Session.
5. **Review.** **Record again** starts a new Take from the same Start Position, and the previous Take is gone. **Discard** leaves no Take. **Keep** lists the Take as "Take N". **Play again** and **Pause** work during Review.
   - Sing at a normal level into the built-in mic: the dB meter reaches about two thirds of the scale.
   - In Review your voice is clearly audible over the backing at the default balance (backing −20 dB).
   - Moving the balance slider changes the backing level live.
   - The balance setting is still there in the next Session, and it also applies when playing kept Takes.
6. **Alignment (loopback).** Load a song with clear transients and record 20 s while holding an earbud against the mic. In Review, the recorded copy should sit on the played audio. It may be off by a few ms, because only the engine's latency estimate and the route's Latency Correction (#9) are applied.
7. **Xrun during a Take (ADR 13).** Record a loopback Take of 3 minutes while using the phone heavily: scroll, switch apps, lock and unlock. Check `adb logcat -s MeaVoxEngine` for `frames corrected after output xruns`. Play the kept Take: it must stay aligned to the end, with no growing offset after a glitch.
8. **Stored Takes.** Play a kept Take from the list: it plays against the Backing Audio from its Start Position and stops at its end. Rename it, then delete it after confirming. Restart the app: the remaining Takes are listed and still play.
9. **End of song.** Start recording 5 s before the end: Review opens by itself when the song ends.
10. **Route change.** Unplug the headphones while recording: the app doesn't crash, the recording ends and Review shows the part recorded so far. Playback and recording work again on the new route.
11. **Leaving the Session.** Press Back during playback: audio stops and the Library shows. Since #34 the Track's screen is the Session.

## Transpose (#10)

Prerequisites: a Ready Track with a sustained note or a clear melody, wired headphones, and the loopback set-up from #8. Watch `adb logcat -s MeaVoxEngine` for the `Transpose: stretcher` line and xruns.

1. **Live change.** Play from 1:00 and step Transpose from 0 to +6 and down to −6 with − / +. Each step is heard at once, the position keeps running, the tempo stays the same, and there are no clicks, dropouts or restarts. Going from 0 to ±1 and back fades over smoothly.
2. **Seek while transposed.** At +3, seek to 2:00 while playing. Playback continues at +3 from 2:00, without a burst of the original key.
3. **Bound to the Take.** At −2, record 10 s. While recording, press + twice: the backing stays at −2 and a note says the new setting applies to the next Take. In Review, the Take plays at −2 and Review shows "Transpose −2". Keep it: the list shows "−2". With the control at 0, play the kept Take: the backing plays at −2.
4. **Alignment when transposed (loopback).** At +4, record a loopback Take over a passage with clear transients. In Review the recorded copy sits on the played audio as closely as at Transpose 0 (#8 item 6): the stretcher adds no offset.
5. **CPU.** At +6, record a 3-minute Take while using the phone. No audible glitches; the xrun correction from #8 item 7 stays small. Then start and stop playback and recording at +6 ten times, and seek a few times: no click or dropout at the start (the stretcher is primed in the first callback).
6. **Last Transpose (#23).** Set +3 without recording, press Back and open another Track: it opens at its own Transpose (0 if it was never changed). Open the first Track again: Transpose is +3 and it plays at +3. Restart the app and open it: still +3. Its kept Takes still play at their own Transpose.

## Separation in the Track Pipeline (#7)

Prerequisites: network for the first run (the Fast model, 75 MB, is downloaded once), a 4-minute and a 9-minute song imported from local files, one of them at 48 kHz (e.g. the audio of a YouTube download). Watch `adb logcat -s WM-WorkerWrapper MeaVoxFlac` for errors.

1. **Downloaded → Separating → Ready.** Fresh install, import a song. The Library shows "Downloading model …%", then "Removing vocals …%", then "Ready", without any action. A notification shows the progress meanwhile.
   - **Notification permission (#27), Android 13+.** On the fresh install no notification prompt appears at app start. When the first Track starts its Separation (the Library shows "Downloading model" or "Removing vocals"), a dialog explains why notifications help. **Allow**: the system prompt appears, and once allowed the progress notification shows. Import a second song: no dialog this time.
   - Reinstall and choose **Not now** (or deny in the system prompt): Separation runs to "Ready" just as fast, with the progress in the Library and no notification. Later Tracks and an app restart don't ask again. Notifications can still be turned on in the system settings.
   - Reinstall, tap **Allow**, and while the system prompt shows, stop the app (`adb shell am force-stop eu.schicht8.meavox`). Open it again: the Separation resumes and the dialog comes back, because an unanswered prompt doesn't count as asked.
   - On Android 12 or lower nothing is asked, and the notification shows.
2. **Original Audio meanwhile.** Open the Track while it is Separating: it plays the Original Audio, and the screen says the instrumental isn't ready yet. Let it play until Separation finishes: playback switches to the Instrumental at the same position, without a jump, and keeps playing.
3. **Instrumental when Ready.** Reopen the Track: it plays the Instrumental, with the lead vocal removed. Start a Session: it plays the Instrumental too.
4. **Resume after process death.** During "Removing vocals" at about 50 %, kill the process (`adb shell am kill eu.schicht8.meavox` after pressing Home, or `adb shell am force-stop` and reopen the app). The job starts again and continues near 50 %, not from 0 %.
5. **Model download resumes.** Turn on airplane mode while the model downloads. The Track stays at "Downloading model …%". Turn it off again: within a few minutes the download continues from where it stopped, not from 0 %.
6. **Reused stems.** Restart the app: the Track is Ready at once and isn't separated again. The stems are in `files/tracks/<hash>/stems/<hash>/` as `instrumental.flac` and `vocals.flac`. No `*.checkpoint` directory is left.
7. **Alignment.** In a Session on the Instrumental, record a loopback Take over a passage with clear transients, then switch the Backing Audio to the Original Audio (or compare in an editor). The drums line up on both, at the start and at the end of the song.
8. **Failure and Retry.** While a Track is still waiting for Separation, replace its Original Audio with a non-audio file (`adb shell run-as eu.schicht8.meavox` and overwrite `files/tracks/<hash>/original`). The Track shows "Vocal removal failed" with a reason, and **Retry** queues it again.
9. **Time and memory.** On the weakest device, note the time from "Removing vocals 0 %" to "Ready" for both songs (expected: about 0.2× the song length) and the peak memory in `adb shell dumpsys meminfo eu.schicht8.meavox`.

## Library details and storage (#20)

Prerequisites: two Ready Tracks, one with two kept Takes. `adb shell run-as eu.schicht8.meavox du -sh files/tracks/*` shows what is on disk.

1. **Library rows.** The Track with Takes shows "2 Takes · Sung … ago"; the other shows "No Takes · Not sung yet". Open a Session on it, go back: it now shows "Sung 0 minutes ago" (or "just now").
2. **Usage.** Open **Storage**. The sizes per Track match `du` within rounding: Original Audio, Stems (Fast), and each Take.
3. **Delete stems.** Delete the stems of one Track after confirming. The Library shows "Stems deleted" and the stems directory is gone. Open the Track: the Original Audio plays, Separation runs again ("Removing vocals …%"), and playback switches to the Instrumental when it finishes. A kept Take still plays in sync over the new Instrumental.
4. **Delete a Take.** Delete one Take: it is gone from Storage and from the Session's Take list, and its `.flac` file is gone.
5. **Delete a Track while separating.** Import a song and, while it shows "Removing vocals", delete it from Storage. The notification goes away, the Track leaves the Library, and its directory under `files/tracks/` is gone and doesn't come back a minute later. Importing the same file again starts fresh.

## Vocal-Free Tracks (#25)

Prerequisites: a karaoke version of a song (audio without a lead vocal) as a local file and as a link, plus a Ready Track with stems. Use `adb shell run-as eu.schicht8.meavox du -sh files/tracks/*` to check the stems on disk.

1. **Import without vocals.** Library ⋮ → **Import file without vocals**. The Track goes to "Ready · Vocal-Free" right after the import, with no "Removing vocals" and no notification. The Track's screen has no Backing Audio choice, and the toggle is on.
2. **Search without vocals.** In a search result's sheet, tap **Add without vocal removal**. After the download the Track is Ready without Separating.
3. **Share, then mark.** Share the karaoke link from a browser. The Track's screen opens with "No vocals in this song?". Tap **Mark Vocal-Free** while it is still downloading: it becomes Ready without Separating. Share a local file the same way and tap it while "Removing vocals" runs: the notification disappears, the Track is Ready, and no stems directory is left.
4. **Mark a Ready Track.** On the Track with stems, turn the toggle on. A dialog names the space freed. Confirm: the stems are gone, playback continues on the original audio at the same position, and a kept Take still plays in sync in a Session.
5. **Unmark.** Turn the toggle off: "Removing vocals" runs, then playback switches to the Instrumental, and Takes stay in sync.
6. **Re-add.** Import the same file again with **Import file without vocals** while it isn't Vocal-Free: the existing Track opens unchanged and keeps separating.

## One screen per Track and the Backing Audio choice (#34)

Prerequisites: a Ready Track with a kept Take, a link to share that isn't in the Library yet, and wired headphones.

1. **One screen.** Open a Track from the Library: lyrics on top, below the status, Lyrics, "Sing over", the transport, Transpose, the microphone, Takes and the Vocal-Free toggle. There is no Sing button.
2. **Opens by itself.** Share the link: the Track's screen shows the download progress and the Lyrics, then the transport appears by itself once the download is done. "No vocals in this song?" shows meanwhile.
3. **Switch while playing.** On the Ready Track, play from 1:00 and switch "Sing over" to **Original Audio** and back: each switch is heard at once at the same place, without stopping, and the kept Take still plays in sync over either.
4. **Kept per Track.** Choose **Original Audio**, press Back and open the Track again: it is still chosen and plays.
5. **Waits for the Take.** Record, and switch while recording: the backing doesn't change and "Switches once this Take is done." shows. After Keep or Discard the new choice plays.
6. **Before Ready.** While a Track is "Removing vocals", **Instrumental** is greyed out with a reason and the original audio plays. If **Original Audio** is chosen when Separation finishes, it stays on the original audio; otherwise it switches to the Instrumental.
7. **Last sung.** Open a Track you've never sung and press Back: the Library still says "Not sung yet". Record a Take: the Library shows "Sung 0 minutes ago" or similar.

## Mix and WAV Export (#14)

Prerequisites: a Ready Track, wired headphones, the loopback set-up from #8, and a file manager or audio editor that shows `Music/MeaVox/`. The automated Seam 2 tests (`./gradlew :engine:connectedDebugAndroidTest` on an emulator or device) cover the render's alignment, levels and Transpose. These checks cover what they can't.

1. **Default Mix.** Set the Review Balance to −14 dB, record a Take from 0:30 and keep it. "Mixes (1)" lists "Mix 1 · Take 1 · Instrumental · Backing −14 dB · Vocal 0 dB · Nudge 0 ms".
2. **Preview.** Open "Edit & export" and tap Preview: the Take plays from its Start Position over the Instrumental, sounding like Review at that balance, and stops by itself at the end of the Take. Drag the Backing and Vocal sliders, and step the Vocal Nudge while it plays: each change is heard at once, without restarting. Stop, close the editor and reopen it: the values are still there.
3. **Mix on another Backing Audio.** Choose Original for the Mix and preview it: the original vocals play under yours. Stop: the Session plays the Instrumental again at the Take's Start Position. The Track's own "Sing over" choice hasn't changed.
4. **Export.** Export at 16-bit: the progress runs to 100 % and "Saved to Music/MeaVox/<Title> (Mix 1).wav" shows. Open the file in another app: it is 16-bit stereo at the Track's rate, as long as the Take, and sounds like the preview, including the Take's Transpose. Export again at 24-bit: a second file appears beside it ("… (1).wav"), 24-bit.
5. **Alignment (loopback).** Record a loopback Take over a passage with clear transients, keep it and export its Mix with Nudge 0. In an editor, the recorded copy sits on the backing as closely as in Review. Export with Nudge +50 ms: it sits 50 ms later.
6. **Cancel.** Start an Export of a long Take and tap Cancel at about 30 %: the progress goes away and no file (not even a partial one) is in `Music/MeaVox/`. Start one again and close the editor: same result.
7. **Share.** After an Export, tap Share: the share sheet opens, and sending to another app (e.g. a messenger or Drive) delivers a playable WAV.
8. **Several Mixes.** Tap "New Mix" on a Take: "Mix 2" opens in the editor. Give it other levels: both Mixes keep their own. Delete Mix 1: the Take and Mix 2 stay. Delete the Take: its Mixes go, and the exported files stay.
9. **Vocal-Free.** On a Track with a Mix on the Instrumental, turn Vocal-Free on: the Mix now says "Original", and its editor shows no Backing Audio choice. Turn it off and let Separation finish: the Mix stays on Original until you choose Instrumental in its editor.
10. **Re-separation.** Re-separate a Track with another model while its Mix is on the Instrumental, delete the old model's stems in Storage, then export the Mix: it uses the new Instrumental and stays in sync.

## Backing Export (#24)

Prerequisites: a Ready Track whose Last Transpose is not 0 (e.g. +2), a Track that is still Separating, and a Vocal-Free Track. You'll also need an app that shows tags (a music player or a file manager's details).

1. **Entry.** On the Track's screen, "Backing track → Export" shows once the song is downloaded, also before vocal removal finishes. The sheet starts on the Backing Audio the Track plays and at its Last Transpose (+2).
2. **Instrumental.** Export the Instrumental at +2 (16-bit): the progress runs to 100 %, and `Music/MeaVox/<Title> (Instrumental, +2).wav` appears. It is the whole song, its length matches the Track, it is 2 semitones up at the same tempo, and it sounds like the Session at +2. The music player shows the title "<Title> (Instrumental, +2)" and the original artist.
3. **Export-only Transpose.** Change the sheet's Transpose to −1 and export the Original Audio: "<Title> (Original, −1).wav". Close the sheet: the Session's Transpose is still +2, and so is the next Session's.
4. **Transpose 0.** Export the Original Audio at 0: "<Title> (Original).wav", indistinguishable from the original.
5. **Before Ready.** On the Separating Track, only the Original Audio is offered, with a note that the instrumental comes once vocal removal is done.
6. **Vocal-Free.** On the Vocal-Free Track, export at +3: no layer choice is shown, and the file is "<Title> (Instrumental, +3).wav".
7. **Cancel and share.** Cancel an export at about 30 %: no file is left in `Music/MeaVox/`. Close the sheet during an export: same result. After an export, Share sends a playable WAV.
8. **24-bit.** Export at 24-bit: the file is 24-bit stereo at the Track's sample rate.

## Latency Correction and Monitoring (#9)

Prerequisites: a Ready Track with clear transients, wired headphones, and if possible a USB interface with headphones and a Bluetooth headset. For a loopback, hold an earbud against the microphone.

1. **Timing hint.** On a route you never corrected, record a loopback Take: Review shows "Timing (wired)" at 0 ms with the hint to check the timing. Keep it: the next Take on that route shows "Kept for this output" instead.
2. **Correcting by ear.** In Review, move "Timing" until the recorded copy sits on the playback: the Take moves while it plays. Keep it, then record and keep another: both play aligned from the list, and so does each one's Mix and its Export.
3. **Earlier Takes stay.** Change the Timing a lot on a third Take and keep it: the first two still play as before.
4. **Per route.** Switch to the speaker and record: Review starts at the speaker's own value. The "No headphones" warning shows before and while recording.
5. **Monitoring over wired.** Monitoring is off when the Session opens. Switch it on in free playback: playback pauses, you hear yourself in the headphones (dry, at the level), the round trip shown drops, and if it is above 40 ms the delay warning shows. Change the level while singing. Record: you hear yourself while recording, and not in Review. The Take sounds the same as without Monitoring. Its Review starts at the low-latency path's own Timing ("wired, Monitoring").
6. **Thinner sound.** With Monitoring on, the Backing Audio may sound thinner (no speaker processing on the low-latency path, ADR 16). Switch it off: the full sound is back.
7. **Not on the speaker or Bluetooth.** On the speaker and over Bluetooth the switch is disabled, with the reason.
8. **USB.** Over a USB interface, the switch is available, and the hint about Hardware Monitoring shows.
9. **Route change.** With Monitoring on, unplug the headphones during playback and during a recording: nothing crashes or freezes, a recording ends in Review with what was recorded, Monitoring is off, and the output shows the speaker. Plug them back in: Monitoring stays off until switched on, and recording works.
10. **Next Session.** Leave and reopen the Track: Monitoring is off, its level is the one set before.
