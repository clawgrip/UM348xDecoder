# UM348xDecoder

Reverse-engineered ROM decoder and WAV synthesizer for the UM348x family of
melody-generator ICs. Verified against two devices: the **UM3481A** (8
melodies) and the **UM3482A** (12 melodies).

## Usage

```bash
javac UM348xDecoder.java
java UM348xDecoder <inputDir> <outputDir>
```

The input directory is scanned for either or both chips' ROM sets. Recognised
note-ROM names are `um3481araw.bin` and `um3482araw.bin`; the offsets and
tempos files are located by matching prefix, falling back to the bare names
`offsets.bin` / `tempos.bin`. Output files are named `<chip>_melody_NN.wav`.

## What is established

| Element | Status |
|---|---|
| Note-ROM addressing (64x56 cell array, `subCol*64 + row`) | Confirmed on both chips |
| Note word format (3-bit duration + 4-bit tone, rest = tone code 3) | Confirmed on both chips |
| Slot pointer packing (16-bit on one dump, 12-bit packed on the other) | Confirmed |
| Tone code to frequency | Per-chip lookup tables; the two devices differ |
| Tone code 1 is a silent control word, not a note | Confirmed on both chips |
| Duration code to ticks: 6 of 8 entries measured; durations are additive | Measured |
| Repeated-tone words sound as one continuous note | Confirmed, two independent lines of evidence |
| Tick length is an integer multiple of 20.48 ms | Confirmed, 4 multipliers observed |
| Which multiplier a given slot uses | **Unresolved** |

## Main open questions

- **Tempo selection.** Tick length is always a multiple of 20.48 ms, and the
  multiplier is known for five specific slots, but it is not recoverable from
  the tempo byte. Bytes 72 and 126 both give 5; bytes 41, 44, 72, 80 give 6,
  4, 5, 3. The two chips also share 13 of 16 tempo-ROM byte values despite
  carrying entirely different songs, which argues this ROM is not per-song
  tempo data at all. Slots without a measured multiplier fall back to 5, so
  they have correct pitch and relative rhythm but possibly the wrong absolute
  speed.
- **The two devices are voiced differently.** Six tone codes measure identical
  on both chips, but code 11 is 393.70 Hz on the UM3482A and 1315.79 Hz on the
  UM3481A -- both from exact alignments. The UM3482A never produces a period
  below 84 samples; the UM3481A regularly reaches 72. A single shared table is
  therefore impossible, and the UM3481A's full table cannot be borrowed for
  the UM3482A's five unmeasured codes (7, 9, 13, 14, 15). Those five are
  estimated within the UM3482A's observed pitch range and flagged "~" in the
  run output.
- **What the control word selects.** Tone code 1 is silent, and on the UM3482A
  a rest followed by a control word occurs nine times -- four at slot starts,
  five *inside* slots 14, 15 and 16. Slot 16 alone contains four, which fits
  other evidence that it holds several melodies and that the offsets table
  does not delimit songs one-to-one. Whether the word marks song starts,
  selects an instrument, or selects tempo is undetermined.
- **Staccato articulation.** One melody per chip is rendered with every note
  chopped into short pulses (~72 ms of silence between them), presumably the
  family's "multi-instrument" feature. Nothing in the note words distinguishes
  these melodies, so this is not reproduced; they render as sustained tones.
- **Duration codes 1, 2 and 4** are the least certain entries in the duration
  table. Code 2 in particular measures 15 ticks where 16 would fit the
  otherwise tidy 1,2,3,4,6,8,12 progression.

## Accuracy against real playback

Generated length versus the logic-level capture, for every melody whose ROM
region aligns exactly with a recording:

| Melody | Generated | Real | Difference |
|---|---|---|---|
| UM3481A slot 1 | 26.73 s | 26.21 s | +2.0% |
| UM3481A slot 2 | 28.51 s | 28.34 s | +0.6% |
| UM3481A slot 3 | 34.53 s | 33.91 s | +1.8% |
| UM3481A slot 4 | 17.76 s | 18.62 s | -4.6% |
| UM3482A slot 9 |  6.76 s |  6.55 s | +3.1% |

Synthesized pitches match the measured periods to within 0.02%.

Full technical detail, including the evidence behind every constant and the
validation methodology, is in the header comment of `UM348xDecoder.java`.
