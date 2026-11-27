# Data safety answers for MeaVox

For the Play Console's Data safety form (App content → Data safety). Based on what the app sends
(see [`privacy-policy.md`](privacy-policy.md)): the lyrics lookup at LRCLIB and the model downloads from
GitHub. Check the answers against Google's current definitions before submitting
(<https://support.google.com/googleplay/android-developer/answer/10787469>).

## Data collection and security

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | The lyrics lookup sends a song's title and artist to LRCLIB, which Play counts as data leaving the device. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Every request uses HTTPS. |
| Do you provide a way for users to request that their data is deleted? | **No** | The developer keeps no user data; everything the app stores is on the device and is deleted with the app. |

## Data types

| Data type | Collected | Shared | Processing | Required or optional | Purpose |
|---|---|---|---|---|---|
| App activity → **In-app search history** (a song's title and artist, sent to LRCLIB to find its lyrics) | **Yes** | **No** | Ephemeral: used only to answer the request | Required (lyrics are looked up for every song; the user can't turn it off) | **App functionality** |

Every other data type: **not collected**, in particular:

- Audio → Voice or sound recordings: **No**. Takes are recorded with the microphone but never leave the device.
- Audio → Music files, Other audio files: **No**. Imported songs stay in the app's private storage.
- Personal info, Financial info, Health and fitness, Messages, Photos and videos, Files and docs, Calendar, Contacts, Location, Web browsing, App info and performance, Device or other IDs: **No**.

Notes on the answers:

- **Shared: No.** Sending data to a third party is "sharing" unless it is user-initiated or the third party is
  the developer's service provider. The title and artist go to LRCLIB only to fetch the lyrics the user sees;
  if Google's reviewers count LRCLIB as a third party the data is shared with, change the answer to **Shared:
  Yes, App functionality**.
- **Model downloads** from GitHub send no user data (a plain file download), so they add no data type.
- The singer's name (a setting) is written into exported files on the device and is never sent.

## Security practices

- Data is encrypted in transit: **Yes**.
- Independent security review: **No**.
