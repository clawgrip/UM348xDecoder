# UM348xDecoder

Reverse-engineered ROM decoder and WAV synthesizer for the UM348x family of
melody-generator ICs. Verified against two devices: a **UM3481A** playing 8
melodies and a **UM3482A** playing 12.

Melody count and slot count differ. Both parts have 16 addressable slots and
both dumps fill all 16 pointers, so the program renders 16 WAVs per part; the
recorded devices simply stop earlier. Which pointers a part actually exposes is
not visible in the dumps.

Based on previous work from:
- Sean Riddle: https://www.seanriddle.com/um348x/
- ArcadeHacker: https://arcadehacker.blogspot.com/2020/07/um3481a-series-multi-instrument-melody.html

## Usage

```bash
mvn package
java -cp target/classes org.recreativas.mame.UM348xDecoder <inputDir> <outputDir>
```

The packaged JAR is executable too:

```bash
java -jar target/um348xdecoder.jar <inputDir> <outputDir>
```

or without Maven:

```bash
javac -d target/classes src/main/java/org/recreativas/mame/UM348xDecoder.java
java -cp target/classes org.recreativas.mame.UM348xDecoder <inputDir> <outputDir>
```

The ROM dumps are in the repository root, so `.` works as the input directory.
It is scanned for either or both devices' ROM sets. Recognised
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
| Tone code to frequency | Per-device lookup tables selecting from one shared divisor pool |
| Tones are f_osc/(2N) for 7-bit divisors N = 36..127 | Confirmed on both devices |
| Duration code to ticks; durations are additive across a run | 4 of 8 entries counted directly, 2 more from ratios |
| Tick length is an integer multiple of 20.48 ms | Confirmed, 4 multipliers observed |
| Staccato articulation: one pulse per tick, 1024 cycles sounding | Confirmed |
| Tone codes are drawn from one 17-value family divisor pool | Confirmed; neither part's 14 values contain the other's |
| The control word consumes time, like a rest of its duration code | Confirmed |
| The first rest of a slot lasts a fixed 8 base units | Confirmed |
| The 8x7-bit ROM is a family-wide 3-to-7 decoder | Structure confirmed; role unknown |
| What selects the tempo multiplier | **Unresolved** |

## Accuracy against real playback

All eight UM3481A melodies match their slot note for note (58, 63, 41, 42, 52,
21, 57 and 56 notes, 100% of each).

Against a continuous logic capture of the whole device, generated length
matches the measured note span plus that slot's own leading rest and control
word (which the capture begins after):

| Melody | Generated | Notes + leading silence | Residual |
|---|---|---|---|
| UM3481A slot 1 | 26.788 s | 26.788 s | 0.000 s |
| UM3481A slot 2 | 28.508 s | 28.508 s | 0.000 s |
| UM3481A slot 3 | 34.447 s | 34.446 s | 0.001 s |
| UM3481A slot 4 | 18.964 s | 18.964 s | 0.000 s |
| UM3481A slot 5 | 20.890 s | 20.972 s | -0.082 s |
| UM3481A slot 6 |  9.585 s |  9.584 s | 0.000 s |
| UM3481A slot 7 | 26.542 s | 26.542 s | 0.000 s |

Slot 5's residual is the tail of its final staccato pulse. Synthesized pitches
match the measured divider values exactly.

## Main open questions

- **What selects the tempo multiplier.** Nine multipliers are measured, none
  derivable from the tempo byte or from the slot header. The tempo ROM is in
  any case suspect as per-song data: the two devices carry entirely different
  song sets yet share 13 of its 16 byte values.
- **What the 8x7 selector ROM drives.** Seven of its eight words are one-hot
  and together cover all seven bit positions exactly once; the eighth has two
  bits. It is identical on both devices, so it is fixed family-wide logic
  shaped like a 3-bit-to-7-line decoder. It is not the duration table and not
  the column-group bit order. Nothing in the available data indexes it.
- **The UM3482A recordings come from a different melody mask than its dump.**
  A single continuous capture holds 12 melodies and only **one** can be
  credibly located in the ROM (slot 9: exact note-for-note match, clean
  multiplier of 5 at 0.37% deviation). Two other candidate alignments imply
  multipliers of 3 and 2.5 with 25% and 100% deviation, so they are
  coincidences. The mismatch is structural: 479 recorded notes against real ROM
  data ending at index 423 inclusive, and a first melody of 52 notes against a largest
  slot of 38 words. Because the capture is continuous and unedited, this is not
  a segmentation or splicing artefact. The tone generator is unchanged --
  the device draws its divisors from the same family pool.
- **Five UM3482A tone codes are estimated, not measured**, and are flagged `~`
  in the run output.
- **Duration codes 1 and 4 rest on weaker evidence.** Neither ever appears on a
  sounding note in either dump, so pulse counting cannot reach them. They are
  measured instead on silent words at melody boundaries, which depends on the
  boundary model rather than on a direct count. Codes 5 and 7 rest on duration
  ratios.
- **Which melody uses staccato articulation** is not marked anywhere in the note
  words, so it cannot be applied automatically; those melodies render as
  sustained tones.

Full technical detail, including the evidence behind every constant, is in the
header comment of `src/main/java/org/recreativas/mame/UM348xDecoder.java`.

## Generated output

`output/` is written by a normal run and is listed in `.gitignore`. If a
previous commit tracked it, `git rm -r --cached output` is needed once, since
an ignore rule does not untrack existing files.