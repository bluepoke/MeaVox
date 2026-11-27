# 19. Three Separation Models (Fast, Medium, Best) and a pre-selected Default Separation Model

- Status: Accepted (amends ADR 15; amended by ADR 22, ADR 24)
- Date: 2026-11-18

## Context
ADR 15 offers two Separation Models: Spleeter 2-stems for *Fast* and UVR MDX-Net Inst HQ 4 for *Best*. It rejected UVR MDX-Net Karaoke because its RTF of 1.34 failed the Fast bound and its ratings (3 and 1 of 5) were clearly behind Inst HQ 4 (`docs/spikes/separation-spike.md`).

The gap between the two is large, though. On the weak device, Fast takes about 0.16× the Track's length and sounds like a quick first version (rated 2 and 1), while Best takes about 3.4× (about 13 min for a 4-minute song). Karaoke sits in between on both counts, and it is the only measured model that is cleaner than Spleeter **and keeps backing vocals**. The spike's own listening notes recommend offering all three with their expected durations.

ADR 15 also says the model picker exists, but not which model it starts on, or that the model can be chosen when a Track is added. #17 asks for a default model in Settings that is pre-selected, including in the import dialogs.

## Decision
- **Catalogue.** Three Separation Models, all **downloaded on demand**:
  - *Fast:* **Spleeter 2-stems, fp32** (75 MB). Unchanged from ADR 15.
  - *Medium:* **UVR MDX-Net Karaoke** (`UVR_MDXNET_KARA`, 29.7 MB). RTF ≈ 1.34 on the weak device, so about 5.4 min for a 4-minute song. It estimates the lead vocal and **keeps backing vocals** in the Instrumental. Its STFT parameters (n_fft 6144, dim_f 2048, compensate 1.035) aren't in the ONNX file and ship with the catalogue entry.
  - *Best:* **UVR MDX-Net Inst HQ 4** (59 MB). Unchanged from ADR 15.
- **License gate.** Like Inst HQ 4, the Karaoke weights have no license (`TRvlvr/model_repo` has none, and the UVR GUI's MIT license doesn't cover them). Medium ships **only once its authors license the weights** and allow redistribution from the project's own release asset (ADR 15's distribution rule). That license still has to be asked for; [UVR #2444](https://github.com/Anjok07/ultimatevocalremovergui/issues/2444) so far asks only about Inst HQ 4. If it is refused, the catalogue ships without Medium.
- **Default Separation Model.** A setting in Settings, persisted. It starts at **Fast**, the only model expected to need no license from the UVR authors and the quickest result on any device.
  - **Import dialogs** (adding a search result, a shared link or a local file) have a Separation Model selection with the default **pre-selected**. The user can change it before importing. The new Track is separated with the selected model; the change applies to that Track only and doesn't change the default. Confirming without touching the selection keeps adding a single action. When the "no vocals" option is set (ADR 14), the selection is hidden.
  - The model picker also opens with the default pre-selected when re-separating a Track and when unmarking a Vocal-Free Track (ADR 14). The user can pick another model there.
  - Changing the default never re-separates existing Tracks or changes their stems.
  - If the default model isn't downloaded yet, the usual download consent comes first (size shown). If its license is withdrawn or it leaves the catalogue, the default falls back to Fast.
- **Model picker** keeps ADR 15's rules: for each model it shows the expected duration for the Track and whether it keeps or removes backing vocals. Medium and Fast keep them, Best removes them.

## Note: first implementation (#17, 2026-11-18)
- **Interim download, against the license gate.** Medium and Best are offered in every build before their weights are licensed. Until the project hosts the files (#21), both are downloaded from the UVR model repository (`TRvlvr/model_repo`, release `all_public_uvr_models`), pinned by SHA-256, as Fast is from the sherpa-onnx release. This is a deliberate exception to the license gate above and to ADR 15's distribution rule, decided by the project owner. It has to be resolved before a public release: by the license from UVR #2444, or by taking the models out of the catalogue.
- **The picked model stays on the Track** (`Track.separationModel`): the one picked in the import sheet, or later when re-separating or unmarking Vocal-Free. A retry after a failed Separation uses it. Tracks added before #17 have none and are separated with **Fast**, not the Default Separation Model (#36): Settings sets the default without asking for download consent, so an undownloaded Best would download 59 MB unasked, and the user never picked anything else for these Tracks. Fast is the first model every install downloads, so it normally needs no download.
- **Download consent:** a model not yet installed shows its size in the picker, a note under it, and a confirm button that reads "Download 59 MB and add" (or "… and re-separate"). Confirming is the consent. Picking the default in Settings asks nothing; the consent comes when the model is first used.
- **Expected duration** is the Track's length times the model's RTF on the weak device (0.16, 1.34, 3.4), or relative to the length when that is unknown (a local file before import).
- **Re-separating** keeps the previous model's stems until they are deleted on the storage screen. The Original Audio played meanwhile, as for Stems Deleted (ADR 17); since ADR 22 (#37) the previous model's Instrumental plays, also after the new model failed. A Separation still running is replaced (ADR 10, #28).
- **Mixes** don't exist yet (ADR 7). Switching a Mix to a re-separated Instrumental lands with the Mix ticket.

## Considered Options
- **Keep two tiers (ADR 15).** Rejected: the jump from Fast to Best is about 20× in time, with nothing in between, and only Best removes backing vocals, which isn't always wanted.
- **Inst HQ 3 as Medium.** Rejected: it is slower than Inst HQ 4 at the same rating.
- **Best as the starting default.** Rejected: 13 min or more per song on weak devices is a poor first experience, and Best may not be licensed for the first release.
- **No pre-selection, always ask.** Rejected: one-tap adding (spec, story 5) would gain a required model question each time.
- **Model only in Settings, not in the import dialogs.** Rejected: the user would have to change the default and change it back to separate one Track differently, or separate it twice.

## Consequences
- "Medium" joins the Fast/Best tiers in the UI, the glossary and the spec.
- Karaoke's peak RSS was only measured within a shared launch (1130 MB peak, +593–611 MB), not in a fresh one. It needs a one-model-per-launch measurement with the CPU arena off before release, against the same foreground-service and lmkd limits as Best.
- Karaoke's quality varies a lot by song (rated 3 on "Rolling in the Deep", 1 on "November Rain", where the voice was clearly audible). The picker text must not promise a clean Instrumental.
- Separation jobs must be keyed by model (#28), since a Track can now be separated with any of three.
- Medium takes about 5.4 min for a 4-minute song on the weak device, so like Best it runs as a foreground-service background job, and Android 15's 6-hour daily limit has to be checked for it too.

## Note: all three models in the first release (#21, 2026-11-27)
The interim download above was to be resolved before a public release. The project owner decided to release both apps with all
three models while UVR #2444 is unanswered: Medium and Best stay downloaded from the UVR model repository, pinned by SHA-256, and
the in-app licenses screen says their weights have no license. If the authors refuse, both models leave the catalogue. See ADR 24.
