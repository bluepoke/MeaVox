# 15. Separation Model catalogue after the separation spike

- Status: Accepted (amends ADR 3), amended by [ADR 19](0019-three-separation-models-and-default-model.md) (UVR MDX-Net Karaoke as Medium, pre-selected Default Separation Model)
- Date: 2026-11-17

## Context
The separation spike (#3, findings in `docs/spikes/separation-spike.md`) ran ADR 3's candidate models through ONNX Runtime on one device: a Galaxy Tab S6 Lite (SM-P613, Snapdragon 720G, 3.7 GB RAM, Android 14). That is the weakest target device so far. The songs were "Rolling in the Deep" (234 s) and "November Rain" (557 s).

- **Spleeter 2-stems (fp32)** ran at RTF 0.15–0.16 with a peak RSS of about 630 MB, but its quality was rated 2 and 1 of 5: artifacts, a dull sound, missing hi-hats. It keeps backing vocals in the Instrumental.
- **UVR MDX-Net Inst HQ 4** ran at RTF 3.34–3.41 with a peak RSS of 1191 MB once ORT's CPU arena was off, and was rated 4 on both songs. It also **removes backing vocals**.
- **Demucs v4** (htdemucs, htdemucs_ft) was killed twice by Android's low memory killer, in the foreground, at 2.09–2.29 GB RSS.
- **UVR MDX-Net Karaoke** (RTF 1.34) failed the Fast bound and was clearly worse than Inst HQ 4. **Inst HQ 3** was slower than HQ 4 at the same rating. **RoFormer models** were estimated at 10–34× htdemucs' compute, with unclear weight licenses.
- XNNPACK was slower than the plain CPU execution provider, and NNAPI was no faster (it's also deprecated in Android 15). Turning ORT's CPU arena off saved about 690 MB on Inst HQ 4 at the same speed.
- Every run produced stems exactly as long as the input, with lag 0. Peak RSS was the same on both songs, so chunked Separation keeps memory bounded by one segment.
- The Inst HQ 4 weights have no valid license yet. The UVR authors were asked on 2026-11-17 ([UVR #2444](https://github.com/Anjok07/ultimatevocalremovergui/issues/2444)). The Spleeter weights turned out to be MIT, like its code (see the note on #29).

## Decision
- **Runtime:** ONNX Runtime (`onnxruntime-android`, MIT) on the **CPU execution provider**, 4 threads, with the **CPU arena and memory-pattern optimisation off**. No XNNPACK or NNAPI.
- **Catalogue for the first release.** Both models are **downloaded on demand**, not bundled:
  - *Fast:* **Spleeter 2-stems, fp32** (75 MB). Its weights are MIT (note on #29). It separates up to 11 kHz only, so the Instrumental keeps the band above. Backing vocals stay in the Instrumental. Its quality is that of a quick first version, not a clean karaoke track.
  - *Best:* **UVR MDX-Net Inst HQ 4** (59 MB), **only once its authors license the weights**. It removes backing vocals along with the lead vocal. Its STFT parameters (n_fft 5120, dim_f 2560, compensate 1.019) aren't in the ONNX file, so they ship with the catalogue entry.
  - If the Inst HQ 4 license is refused or doesn't arrive in time, the first release ships Fast only, and another Best candidate is looked for (Demucs with less memory, or another architecture).
- **Not offered:** Demucs v4, UVR MDX-Net Karaoke, Inst HQ 3, RoFormer models (reasons above).
- **Model picker:** for each model it shows the **expected duration** for the Track (Track length × the model's RTF), and whether the model **keeps or removes backing vocals**. The models differ in that, not just in speed and quality.
- **Distribution:** model files are downloaded from a release asset the project controls, with a checksum, never from third-party URLs, so a file can't change under the app. This needs the redistribution permission asked for in UVR #2444.
- **Pipeline:**
  - Separation runs at 44.1 kHz. Original Audio at another rate is resampled with a band-limited, zero-lag resampler, and the stems are resampled back to the Original Audio's rate.
  - Stems have exactly the Original Audio's length.
  - Separation runs in overlapping, cross-faded segments, with memory bounded by one segment.
  - The model estimates one source, and the other stem is the input minus it (at 44.1 kHz). So Instrumental + Vocal Stem equals the resampled Original Audio, band-limited to about 21 kHz.
- **Background job:** the resumable Separation job (ADR 3) is checkpointed per segment, so a killed process resumes where it stopped. It runs as a **foreground service** with a notification. Best peaks at about 1.2 GB RSS on a 3.7 GB device, and Android kills a background process sooner than a foreground one.

## Note: first implementation (#7)
- Until the project hosts the model files (#21), the Fast model is downloaded from the sherpa-onnx GitHub release it was measured with, pinned by SHA-256.
- The foreground service type is `mediaProcessing` from Android 15 and `dataSync` before. Android 12+ may refuse to start a foreground service for a job that starts in the background (e.g. after process death); the job then runs without one and, if the system stops it, continues later from its checkpoint.
- Checkpoints are 4-channel 24-bit FLAC files per segment at the model's rate, and a resumed run decodes the Original Audio again from the start. The overlapping segment before the first missing one is separated again, so the result matches an uninterrupted run exactly.

## Note: interim UVR download (#17, 2026-11-18)
- Medium and Best are downloaded from the UVR model repository, against the distribution rule above, until that is resolved: see ADR 19's note "Interim download, against the license gate".

## Consequences
- "Instrumental" and "Vocal Stem" depend on the Separation Model: whether backing vocals count as removed vocal is up to the model (see `GLOSSARY.md`).
- On the weak device, Fast takes about 0.16× the Track's length and Best about 3.4×, so Best takes about 13 min for a 4-minute song and about 32 min for a 9-minute one. The UI has to treat Best as a long background job.
- The full ORT build adds about 34 MB per ABI (arm64, uncompressed) to the APK. A reduced build with only the operators the shipped models need (ORT's `--include_ops_by_config`) is a follow-up.
- Android 15 limits `dataSync` and `mediaProcessing` foreground services to 6 hours a day. That is enough for Fast; Best (#17) has to be checked against it.
- Only one device was measured. Thread counts 2 / 4 / 8 weren't compared on the CPU provider, and the XNNPACK and NNAPI runs weren't clean one-factor comparisons.
- The spike's shortcuts (no checksums or resumable downloads, raw temp files, 16-bit WAV stems, foreground-only runs) are listed in the findings and must not be copied into production.

## Note: Spleeter weight license confirmed (#29, 2026-11-23)
- Deezer's JOSS paper on Spleeter covers the pretrained models explicitly: "Spleeter … source code and pre-trained models are available on github and distributed under a MIT license." (Hennequin, Khlif, Voituret & Moussallam, *Spleeter: a fast and efficient music source separation tool with pre-trained models*, JOSS 5(50), 2154, 2020, [doi:10.21105/joss.02154](https://doi.org/10.21105/joss.02154)). The repository's [LICENSE](https://github.com/deezer/spleeter/blob/master/LICENSE) is MIT, "Copyright (c) 2019-present, Deezer SA."
- MIT allows redistribution, also by a GPL-3.0-or-later app (ADR 11), as long as the copyright notice and the license text go with it. The text is in [`docs/licenses/MIT-Spleeter.txt`](../licenses/MIT-Spleeter.txt). The project's release asset for the Fast model and the in-app license list (#21) carry it.
- The app uses sherpa-onnx's ONNX export of these weights (#7). The export converts the format and keeps the weights, so the MIT terms still apply.
- So the Fast model can be hosted as a project release asset (#21). Medium and Best are still waiting on the UVR authors (UVR #2444, ADR 19).
