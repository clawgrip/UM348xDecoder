# UM348xDecoder

Reverse-engineered ROM decoder and WAV synthesizer for the UM348x family of
melody-generator ICs. Verified against two devices: the **UM3481A** (8
melodies) and the **UM3482A** (12 melodies).

## Usage

```bash
javac UM348xDecoder.java
java UM348xDecoder <inputDir> <outputDir>
```

The input directory is scanned for either or both devices' ROM sets. Recognised
note-ROM names are `um3481araw.bin` and `um3482araw.bin`; the offsets and tempos
files are located by matching prefix, falling back to the bare names
`offsets.bin` / `tempos.bin`. Output files are named `<chip>_melody_NN.wav`.

## What is established

| Element | Status |
|---|---|
| Note-ROM addressing, including the sub-column order `0,1,2,3,7,6,5,4` | Confirmed on both devices |
| Note word format: 3-bit duration + 4-bit tone, rest = tone code 3 | Confirmed on both devices |
| Tone code 1 is a silent control word, not a note | Confirmed on both devices |
| Slot pointer packing (16-bit on one dump, 12-bit packed on the other) | Confirmed |
| Tone code to frequency | Per-device lookup tables; the devices differ |
| Duration code to ticks; durations are additive across a run | 4 of 8 entries counted directly, 2 more from ratios |
| Tick length is an integer multiple of 20.48 ms | Confirmed, 4 multipliers observed |
| Staccato articulation: one pulse per tick, ~12 ms pulse, fixed gap | Confirmed |
| The 8x7-bit ROM is a family-wide 3-to-7 decoder | Structure confirmed; role unknown |
| What selects the tempo multiplier | **Unresolved** |

## Accuracy against real playback

All eight UM3481A melodies match their slot note for note (58, 63, 41, 42, 52,
21, 57 and 56 notes, 100% of each).

| Melody | Generated | Real | Difference |
|---|---|---|---|
| UM3481A slot 1 | 26.73 s | 26.21 s | +2.0% |
| UM3481A slot 2 | 28.51 s | 28.34 s | +0.6% |
| UM3481A slot 3 | 34.53 s | 33.91 s | +1.8% |
| UM3481A slot 4 | 18.92 s | 18.62 s | +1.7% |
| UM3481A slot 5 | 20.89 s | 20.64 s | +1.2% |
| UM3481A slot 6 |  9.63 s |  9.01 s | +6.8% |
| UM3481A slot 7 | 26.54 s | 26.38 s | +0.6% |
| UM3481A slot 8 | 39.94 s | 39.32 s | +1.6% |
| UM3482A slot 9 |  6.76 s |  6.55 s | +3.1% |

The small positive bias is the leading rest and control word, which the
captures begin after. Synthesized pitches match the measured periods to within
0.02%.

## Main open questions

- **What selects the tempo multiplier.** Ten multipliers are measured, none
  derivable from the tempo byte or from the slot header. The tempo ROM is in
  any case suspect as per-song data: the two devices carry entirely different
  song sets yet share 13 of its 16 byte values.
- **What the 8x7 selector ROM drives.** Seven of its eight words are one-hot
  and together cover all seven bit positions exactly once; the eighth has two
  bits. It is identical on both devices, so it is fixed family-wide logic
  shaped like a 3-bit-to-7-line decoder. It is not the duration table and not
  the column-group bit order. Nothing in the available data indexes it.
- **The UM3482A recordings come from a different song set than its dump.** Only
  3 of its 12 recorded melodies can be located in the ROM, but those three match
  perfectly. The mismatch is structural: 479 recorded notes against real ROM
  data ending at index 424, and a first melody of 52 notes against a largest
  slot of 38 words.
- **Five UM3482A tone codes are estimated, not measured**, and are flagged `~`
  in the run output.
- **Duration codes 1 and 4 cannot be measured from the UM3481A at all** --
  neither ever appears on a sounding note there, so even pulse counting cannot
  reach them. Codes 5 and 7 rest on duration ratios rather than counted pulses.
- **Which melody uses staccato articulation** is not marked anywhere in the note
  words, so it cannot be applied automatically; those melodies render as
  sustained tones.

Full technical detail, including the evidence behind every constant, is in the
header comment of `UM348xDecoder.java`.
