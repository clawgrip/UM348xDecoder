package org.recreativas.mame;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ============================================================================
 *  UM348xDecoder
 *  Reverse-engineered ROM decoder and WAV synthesizer for the UM348x family of
 *  melody-generator ICs. Verified against two devices: a UM3481A playing 8
 *  melodies and a UM3482A playing 12.
 *
 *  Melody count and slot count are different things. Both parts have 16
 *  addressable slots, and both dumps fill all 16 pointers, so this program
 *  renders 16 WAVs per part. The recorded devices play fewer: the UM3481A
 *  stops after 8, and the recorded UM3482A after 12. Which pointers a part
 *  actually exposes is not visible in the dumps.
 * ============================================================================
 *
 * INPUT
 * -----
 * Per device, three raw ROM dumps:
 *
 *   [chip]raw.bin     (448 bytes)  Main note ROM. 448 bytes = 3584 bits =
 *                                  64 rows x 56 columns, matching the physical
 *                                  cell array reported from die inspection of
 *                                  this family: 7 groups of 8 columns.
 *
 *   [chip]offsets.bin              Melody start pointers, one per addressable
 *                                  slot (see section 2 for the two packings
 *                                  observed).
 *
 *   [chip]tempos.bin  (16 bytes)   One byte per slot, from the ROM bank
 *                                  conventionally described as tempo. Its
 *                                  actual role is not established (section 6).
 *
 * A fourth ROM area exists on the die, 8 words of 7 bits, and is described in
 * section 7. It is not read by this program.
 *
 * Based on previous work from:
 *   - Sean Riddle: https://www.seanriddle.com/um348x/
 *   - ArcadeHacker: https://arcadehacker.blogspot.com/2020/07/um3481a-series-multi-instrument-melody.html
 *
 * No manufacturer documentation of the internal note/duration/tempo encoding
 * is published, so every parameter here was derived from the devices' own data
 * and calibrated against logic-level captures of real playback. Sections 1-10
 * describe what the data establishes; section 11 collects what remains
 * unknown or uncertain.
 *
 * Ground truth: four logic-level captures at 100 kHz -- two covering all 12
 * UM3482A melodies between them, and two covering all 8 UM3481A melodies. All
 * are two-level signals (the raw square wave driving the speaker pin), which
 * allows sample-exact period and duration measurement. Methodology in
 * section 9.
 *
 * ----------------------------------------------------------------------------
 * 1. PHYSICAL NOTE-ROM ADDRESSING
 * ----------------------------------------------------------------------------
 * The 448-byte note ROM is read row-major: row r (0-63), 7 bytes per row (one
 * per physical column-group g, 0-6). Each byte holds that group's bit for the
 * 8 "sub-columns" sharing the row; sub-column s (0-7) is bit s of the byte
 * (s = 0 -> least-significant).
 *
 * Melodies run through the sub-columns in this order, not 0..7:
 *
 *     0, 1, 2, 3, 7, 6, 5, 4          (SUBCOLUMN_ORDER)
 *
 * i.e. the second half of the array is traversed in reverse, so
 *
 *     noteIndex = position_in_SUBCOLUMN_ORDER * 64 + row      (0..511)
 *
 * Three independent lines of evidence fix this order:
 *
 *   - Melody continuity. Aligned as one uninterrupted stream, a UM3481A
 *     recording matches the ROM for 190 notes and stops dead at logical index
 *     256 -- exactly a sub-column boundary (4 x 64) -- with the notes that
 *     follow found at physical sub-column 7. Under the order above, every
 *     UM3481A melody aligns exactly with its slot: 58, 63, 41, 42, 52, 21, 57
 *     and 56 notes, 100% of each, in slot order.
 *   - Slot pointers land correctly. A capture holding that device's last
 *     melodies aligns 134 of 134 notes starting precisely at ROM index 329,
 *     which is offsets[5], the start of slot 6.
 *   - UM3482A slot occupancy becomes sane: no slot is empty and slot sizes run
 *     a uniform 14 to 38 words, where the naive 0..7 order gives two slots of
 *     pure silence and one of 97 melodic words.
 *
 * ----------------------------------------------------------------------------
 * 2. NOTE WORD FORMAT AND SLOT POINTERS
 * ----------------------------------------------------------------------------
 * Each note is a 7-bit word read in the bit order the physical layout already
 * provides (group 0 = MSB, group 6 = LSB; no group permutation):
 *
 *     bits 6-4 (3 bits)  ->  DURATION  (raw code 0-7)
 *     bits 3-0 (4 bits)  ->  TONE      (raw code 0-15)
 *
 * Tone code 3 is silence/rest on both devices: it opens most melody slots and
 * fills unused space entirely. Tone code 1 is a silent control word
 * (section 3a). The other 14 codes are sounding notes.
 *
 * The two offset dumps use different bit packings, detected by file size:
 *
 *   32 bytes -> 16 entries of 16 bits, big-endian       (UM3482A dump)
 *   24 bytes -> 16 entries of 12 bits, packed MSB-first (UM3481A dump)
 *
 * The 12-bit reading is confirmed by its output: 0, 88, 165, 213, 271, 329,
 * 358, 421 followed by 499 repeated eight times -- eight strictly increasing
 * pointers for a device with eight melodies, then a repeated filler value.
 *
 * The "14 selectable tones" figure often quoted for this family is a property
 * of one song set rather than of the hardware: tone codes 0 and 5 never occur
 * in the UM3482A ROM, but the UM3481A uses code 5 heavily (61 occurrences) and
 * code 0 once. Across both devices all 16 codes occur.
 *
 * ----------------------------------------------------------------------------
 * 3. TONE -> FREQUENCY  (per-device lookup tables; the devices DIFFER)
 * ----------------------------------------------------------------------------
 * The mapping is a direct lookup, not a computable scale formula, and it is
 * not shared between the two devices.
 *
 * UM3481A -- all 14 sounding codes present in its ROM are measured, from
 * melodies that align note-for-note with recordings, with no conflicting
 * readings:
 *
 *     code  period  freq          code  period  freq
 *       0     202   495.05 Hz       9     106   943.40 Hz
 *       2     142   704.23 Hz      10     126   793.65 Hz
 *       4     170   588.24 Hz      11      76  1315.79 Hz
 *       5      96  1041.67 Hz      12     150   666.67 Hz
 *       6     114   877.19 Hz      13      84  1190.48 Hz
 *       7      72  1388.89 Hz      14     100  1000.00 Hz
 *       8     190   526.32 Hz      15      90  1111.11 Hz
 *
 * UM3482A -- seven codes measured. Six agree with the UM3481A values, but code
 * 11 does not, and the disagreement is large and unambiguous:
 *
 *     code 11 ->  254 samples =  393.70 Hz on the UM3482A
 *     code 11 ->   76 samples = 1315.79 Hz on the UM3481A
 *
 * Both readings come from exact alignments (2 observations on the UM3482A, 5
 * across three separate UM3481A melodies), so the same code sits near the
 * bottom of one device's range and near the top of the other's. The overall
 * ranges differ to match: the UM3482A recordings never produce a period below
 * 84 samples, while the UM3481A regularly reaches 76 and 72. The UM3482A is
 * voiced lower.
 *
 * UM3482A codes 7, 9, 13, 14 and 15 have no measured value and are estimated
 * (11.1). Codes 0 and 5 never occur in that ROM.
 *
 * ----------------------------------------------------------------------------
 * 3a. TONE CODE 1 IS A CONTROL WORD, NOT A NOTE
 * ----------------------------------------------------------------------------
 * Raw tone code 1 produces no sound. In every passage that aligns exactly with
 * a recording, the first audible note corresponds to the ROM word AFTER the
 * tone-1 word, never to the tone-1 word itself -- confirmed independently on
 * UM3481A slots 1 and 4 and on UM3482A slot 7.
 *
 * Where these words sit is informative. On the UM3482A a rest word immediately
 * followed by a tone-1 word occurs at nine places, of which four are slot
 * starts and five are inside slots. It is treated here as a rest: silent, but
 * consuming its word's duration: the time it takes is established by the
 * boundary measurements of section 6a, where a control word's silence is
 * accounted for to within 0.57%. What it selects is open (11.2).
 *
 * ----------------------------------------------------------------------------
 * 3b. THE TONE TABLE IS A SET OF 7-BIT CLOCK DIVIDERS
 * ----------------------------------------------------------------------------
 * Every period ever measured on either device is an EVEN number of samples at
 * the 100 kHz capture rate: 72, 76, 84, 90, 96, 100, 106, 114, 126, 134, 142,
 * 150, 170, 190, 202, 226 and 254. The half-periods are therefore integers,
 *
 *     N = 36, 38, 42, 45, 48, 50, 53, 57, 63, 67, 71, 75, 85, 95, 101, 113, 127
 *
 * spanning 36 to 127 and so fitting exactly in 7 bits -- the word width of
 * these ROMs. A tone is thus produced by toggling the output every N cycles of
 * the master oscillator, giving
 *
 *     f_tone = f_osc / (2N)
 *
 * and in the reference captures the half-periods come out as exact integers
 * with no jitter at all -- across 311392 transitions of a continuous capture,
 * 99.85% of the run lengths land on one of the divisor values above, and the
 * neighbouring lengths occur 0 to 3 times each. One captured sample therefore
 * corresponds to exactly one oscillator cycle, and the natural unit of the
 * whole design is the oscillator cycle rather than the second. The timing base
 * agrees: one tick unit is 2048 cycles, i.e. 2^11, a plain binary divider off
 * the same oscillator.
 *
 * The divisor pool is shared by the family. A continuous capture of each device
 * gives 14 distinct divisors apiece, drawn from a common inventory of 17: the
 * two sets overlap in 11 values, the UM3482A additionally reaching lower with
 * 67, 113 and 127 and the UM3481A higher with 36, 38 and 45. Neither set
 * contains the other. This is the same tone generator with a different melody
 * mask, which is why the per-device frequency tables of section 3 differ only
 * in which divisor each code selects.
 *
 * Absolute time in this program follows the captures' nominal 100 kHz rate, so
 * TEMPO_BASE_UNIT_MS is 20.48 ms and pitches are f_osc/(2N) with f_osc taken as
 * 100 kHz. Should the true oscillator frequency differ, every pitch and every
 * duration scales together by the same factor and all internal ratios are
 * unaffected.
 *
 * The frequencies in section 3 are therefore not arbitrary tuning values but
 * f_osc/(2N) for a small set of 7-bit divisors, and the tone ROM (not dumped)
 * is expected to hold those divisors directly. This also constrains the
 * estimates of 11.1: an estimated code must correspond to an integer N in the
 * observed range, which all of them do.
 *
 * Since f_osc is an on-chip RC oscillator, absolute pitch will drift with part,
 * supply and temperature; only the ratios between tones and the ratio of tone
 * to tempo are fixed by the design.
 *
 * ----------------------------------------------------------------------------
 * 4. DURATION CODE -> TICKS
 * ----------------------------------------------------------------------------
 * Duration is a lookup, not linear in the raw code:
 *
 *     code : 0   1    2     3   4   5   6    7
 *     ticks: 2   3    15    4   1   8   12   6
 *
 * Codes 0, 2, 3 and 6 are established by DIRECT COUNTING. In the staccato
 * melody the device re-articulates each note once per tick (section 8), so the
 * pulses can simply be counted, with no ratios, no multiplier and no
 * calibration involved. An isolated capture of that melody yields 254 bursts, of
 * which the first and last are partial edge artefacts of the capture; the
 * remaining 252 group into 52 notes and give, with no exception anywhere:
 *
 *     code 0  -> 2 ticks   (20 independent notes, every one exactly 2 pulses)
 *     code 3  -> 4 ticks   (24 independent notes, every one exactly 4 pulses)
 *     code 2  -> 15 ticks  ( 4 independent notes, every one exactly 15 pulses)
 *     codes 6+0 in a run -> 14 pulses, so code 6 = 12 ticks ( 4 notes)
 *
 * which totals 20*2 + 24*4 + 4*15 + 4*14 = 252.
 *
 * The value 15 for code 2 is therefore exact and not a rounding of 16: a
 * fifteen-pulse note counted four times cannot be sixteen. The same holds for
 * code 6 at 12.
 *
 * Codes 5 and 7 come from duration ratios measured on aligned melodies, in
 * units where the shortest observed word is 1 (spread under 1%): code 5 at
 * 3.995 and code 7 at 3.010, giving 8 and 6 ticks.
 *
 * Codes 1 and 4 never occur on a sounding note in either dump, so they cannot
 * be counted or timed as notes. They are nevertheless measured, on silent
 * words at melody boundaries (6a): a rest of code 1 occupies 3 ticks and a
 * control word of code 4 one tick, each confirmed against the modelled
 * boundary silence. That is weaker evidence than the pulse counts above,
 * since it rests on the boundary model rather than on a direct count (11.4).
 *
 * ADDITIVITY -- when consecutive words share a tone code, the resulting note's
 * length is the plain SUM of the member words' ticks. The pulse counts confirm
 * this exactly: a run of codes 6 and 0 counts 14 pulses against a predicted
 * 12 + 2. Duration-ratio measurements agree:
 *
 *     run (3,3)   measured 3.997  predicted 3.999
 *     run (3,3,3) measured 5.990  predicted 5.998
 *     run (3,5)   measured 5.990  predicted 5.994
 *     run (5,3)   measured 6.015  predicted 5.994
 *
 * Because notes are rendered back to back with no gap, concatenating each
 * word's independently computed duration already performs this summation.
 *
 * ----------------------------------------------------------------------------
 * 5. CONSECUTIVE REPEATED-TONE WORDS
 * ----------------------------------------------------------------------------
 * A run of consecutive words carrying the same tone code may be rendered
 * either as one sustained note or as several re-articulated ones; alignment
 * against the recordings requires allowing both, and a melody generally cannot
 * be matched at all if runs are forced to collapse completely.
 *
 * Which of the two happens cannot be settled from these captures: the
 * edge-extraction step used to read them merges any uninterrupted stretch of
 * one period into a single event, so a re-articulation of the same pitch with
 * no intervening silence is indistinguishable from one long note.
 *
 * This does not affect synthesis. Either way the run's total length is the sum
 * of the member words' ticks (section 4), and notes are rendered contiguously,
 * so emitting each word in turn gives the correct total. What does matter is
 * waveform CONTINUITY: a song-wide sample counter supplies the phase, so runs
 * of equal pitch render without a discontinuity at each internal boundary,
 * while genuine pitch changes still switch abruptly, as the captures show.
 *
 * ----------------------------------------------------------------------------
 * 6. TEMPO
 * ----------------------------------------------------------------------------
 * Tick length is always an integer multiple of a base unit of 20.48 ms (2048
 * samples at 100 kHz, i.e. 2^11). Four multipliers occur across the two
 * devices, each pinned to a specific slot:
 *
 *     slot            tempo byte    multiplier    tick
 *     UM3481A slot 1      72            5        102.40 ms
 *     UM3481A slot 2      44            4         81.92 ms
 *     UM3481A slot 3      41            6        122.88 ms
 *     UM3481A slot 4      80            3         61.44 ms
 *     UM3481A slot 5      14            4         81.92 ms
 *     UM3481A slot 6      36            5        102.40 ms
 *     UM3481A slot 7      54            4         81.92 ms
 *     UM3481A slot 8      91            6        122.88 ms
 *     UM3482A slot 9     126            5        102.40 ms
 *
 * Each figure is the median per-note ratio between measured length and
 * predicted ticks across a fully aligned melody, and every one lands within
 * 0.1% of an integer. Slots not listed use the fallback multiplier of 5.
 *
 * What selects the multiplier is not established, and is not any of the data
 * this program reads (11.5).
 *
 * ----------------------------------------------------------------------------
 * 6a. TIMING AT MELODY BOUNDARIES
 * ----------------------------------------------------------------------------
 * A continuous logic capture of all eight UM3481A melodies makes the silence
 * between melodies measurable to a fraction of a base unit. Every boundary is
 * accounted for by two rules, with a worst discrepancy of 0.046 base units
 * (0.57%) across all seven:
 *
 *   - The FIRST word of a slot, when it is a rest, lasts exactly 8 base units,
 *     regardless of the slot's multiplier and of the word's own duration code.
 *     This holds at all seven boundaries, where the slot multipliers involved
 *     range over 3, 4, 5 and 6.
 *   - Every later word, control words included, lasts its duration code's
 *     ticks multiplied by the slot's multiplier.
 *
 *     boundary  words     model                          measured (base units)
 *       1|2     R0        8                               8.000
 *       2|3     R0 R1     8 + 3*6 = 26                   26.000
 *       3|4     R0 C1     8 + 3*3 = 17                   17.046
 *       4|5     R0 C4     8 + 1*4 = 12                   12.020
 *       5|6     R0 C3     8 + 4*5 = 28                   27.998
 *       6|7     R0        8                               8.022
 *       7|8     R0 R1     8 + 3*6 = 26                   26.008
 *
 * The 4|5 boundary shows the control word's transition directly: the silence
 * is split by a lone edge at 8.021 base units, exactly where the leading rest
 * ends and the control word begins.
 *
 * Within this device the slot header also predicts the multiplier exactly --
 * a control word of duration code 3 gives 5, of code 1 gives 3, of code 4
 * gives 4; a rest of duration code 1 as second word gives 6; a plain note as
 * second word gives 4 -- covering all eight slots. This does not carry across
 * devices (11.5).
 *
 * ----------------------------------------------------------------------------
 * 7. THE 8 x 7-BIT SELECTOR ROM
 * ----------------------------------------------------------------------------
 * A fourth ROM area on the die holds 8 words of 7 bits. Its content is
 * IDENTICAL on both devices:
 *
 *     word 0  0000110   (bits 1 and 2)
 *     word 1  0001000   (bit 3)
 *     word 2  1000000   (bit 6)
 *     word 3  0000001   (bit 0)
 *     word 4  0000010   (bit 1)
 *     word 5  0000100   (bit 2)
 *     word 6  0100000   (bit 5)
 *     word 7  0010000   (bit 4)
 *
 * Seven of the eight words are one-hot, and between them they cover all seven
 * bit positions exactly once -- a permutation of 7 lines. Word 0 is the sole
 * exception, with two bits set. Being identical across two devices with
 * entirely different song sets, it is fixed family-wide logic rather than
 * per-song data, and its shape is that of a DECODER: a 3-bit index selecting
 * one of seven output lines.
 *
 * Two readings are excluded by the playback data:
 *
 *   - It is not the duration table. As values it is {1,2,4,6,8,16,32,64},
 *     which shares 1, 2, 4, 6 and 8 with the measured table but cannot
 *     represent 12 or 15 ticks. Both are counted directly, pulse by pulse, in
 *     the staccato melody (section 4), so neither can be a mis-measured 16.
 *     Over all 40320 assignments of these values to the eight duration codes,
 *     the best leaves a 12.1% error in some melody's total length, against
 *     4.7% for the measured table.
 *   - It is not the column-group bit order. Used as a bit permutation for the
 *     7 groups it aligns zero notes, where the identity order aligns all 58 of
 *     the first UM3481A melody.
 *
 * What it does select is open (11.6).
 *
 * ----------------------------------------------------------------------------
 * 8. STACCATO / TREMOLO ARTICULATION
 * ----------------------------------------------------------------------------
 * One melody per device is rendered with every note chopped into short pulses
 * separated by silence. The rule is exact: the device emits ONE PULSE PER
 * TICK. From an isolated capture of the UM3481A's staccato melody:
 *
 *     pulses                254
 *     gap between pulses    7168 oscillator cycles = 3.5 base units, exactly
 *     onset to onset        8192 cycles = 4 base units = one tick at
 *                           multiplier 4
 *
 * so the tone sounds for 8192 - 7168 = 1024 cycles, i.e. 2^10, exactly half a
 * base unit, at the head of every tick, and the remainder of the tick is
 * silent. The sounding part is a fixed length, not a proportion of the note.
 * On the UM3482A the gap measures about 93 ms.
 *
 * Grouping consecutive equal-pitch pulses back into notes recovers the melody
 * exactly: after discarding the two partial bursts at the ends of the capture,
 * 252 pulses reduce to 52 notes whose tone codes match slot 5 word
 * for word, and whose pulse counts match the tick counts of section 4 in every
 * case. This makes the articulation the most precise measuring instrument
 * available on these devices -- it renders the tick counter directly audible,
 * which is what fixes four entries of the duration table by counting.
 *
 * The articulation is not reproduced by this program because nothing in the
 * note words marks the melodies that use it (11.7); they render as ordinary
 * sustained tones, correct in pitch and total duration but not in texture.
 *
 * ----------------------------------------------------------------------------
 * 9. VALIDATION METHODOLOGY
 * ----------------------------------------------------------------------------
 *   a. Each capture is binarized (two levels only) and rising edges extracted;
 *      runs of equal cycle period become note events, long non-oscillating
 *      stretches become rests.
 *   b. Melodies are located in the ROM without presupposing slot boundaries.
 *      Two search modes are used. A canonical form (each distinct value
 *      renumbered by order of first appearance) compared by longest-common-
 *      subsequence needs no frequency table and so can bootstrap one. Given a
 *      table, an exact search is possible: convert measured periods to tone
 *      codes and find ROM windows matching "code1+ code2+ ... codeN+", which
 *      both localizes the melody and recovers the word-to-note grouping.
 *   c. Every figure quoted in sections 3-8 comes from the exact search.
 *
 * One property of this evidence governs how it must be read: subsequence
 * alignment guarantees the compared PATTERNS match but not that individual
 * positions correspond, when a melody revisits a pitch class. Only exact
 * matches are used for the lookup tables.
 *
 * ----------------------------------------------------------------------------
 * 10. AUDIO SYNTHESIS
 * ----------------------------------------------------------------------------
 * A pure two-level square wave, no envelope, no inter-note gap, with an
 * immediate frequency change at note boundaries (a single transitional cycle
 * is visible when the divider ratio changes). Phase runs continuously across
 * each song, per section 5.
 *
 * ----------------------------------------------------------------------------
 * 11. OPEN QUESTIONS AND UNCERTAINTIES
 * ----------------------------------------------------------------------------
 * 11.1 UM3482A TONE CODES 7, 9, 13, 14, 15 ARE ESTIMATED, NOT MEASURED.
 *      None appears in a UM3482A passage that can be aligned exactly. They are
 *      not given the UM3481A values, since code 11 shows the two tables can
 *      differ drastically. Each is assigned a period the UM3482A is observed
 *      to produce, preserving the relative pitch order those codes have on the
 *      UM3481A. Each estimate is a divisor the UM3482A is actually observed to
 *      use, so no estimated code can emit a pitch the device never makes, and
 *      the assignment consumes five of the seven unassigned divisors in its
 *      inventory, leaving 101 and 113 spare. The individual assignments may
 *      still be wrong. Affected slots are flagged "~" in the run output.
 *
 * 11.2 WHAT THE TONE-1 CONTROL WORD SELECTS IS UNKNOWN.
 *      Song-start marking, instrument or articulation select (11.7) and tempo
 *      select are all consistent with where the word appears. Its duration
 *      field varies between occurrences, which would suit a parameter-carrying
 *      word.
 *
 * 11.3 WHY THE FIRST REST OF A SLOT IS A FIXED 8 BASE UNITS IS NOT EXPLAINED.
 *      The measurement is unambiguous across seven boundaries and four
 *      different multipliers (6a), but no mechanism is known for it. Eight
 *      base units is 16384 oscillator cycles, i.e. 2^14, so a plain binary
 *      divider is the obvious suspect: the pause may simply be generated by
 *      fixed logic at melody start rather than by the note engine.
 *
 * 11.4 DURATION CODES 1 AND 4 ARE NOT MEASURED, AND CANNOT BE FROM THE
 *      UM3481A. On that device neither code ever appears on a sounding note --
 *      only on rests and control words -- so no amount of further UM3481A
 *      audio can pin them, including the pulse-counting method of section 8.
 *      They can only come from an alignable UM3482A melody that uses them, or
 *      from a dump of the duration table itself. Codes 5 and 7 are one step
 *      weaker than the rest of the table, resting on duration ratios rather
 *      than on counted pulses.
 *
 * 11.5 THE TEMPO MULTIPLIER'S SOURCE IS UNIDENTIFIED.
 *      It is not the tempo byte: bytes 72 and 36 both yield 5, bytes 44 and 54
 *      both yield 4, bytes 41 and 91 both yield 6, and byte 80 yields 3, with
 *      no linear, proportional, modular, bitwise or bit-reversed function
 *      fitting. That ROM is in any case suspect as per-song data, since the two
 *      devices carry entirely different song sets yet share 13 of its 16 byte
 *      values, with zero-based offsets 1 to 8 identical in both and offset 12
 *      matching as well.
 *
 *      Nor is it the slot header. Within the UM3481A the header shape predicts
 *      all eight multipliers, but across devices it fails at once: UM3482A slot
 *      9 opens with a rest of duration code 0 followed by a plain note -- the
 *      shape that means 4 on the UM3481A -- and measures 5.
 *
 * 11.6 WHAT THE 8 x 7 SELECTOR ROM DRIVES IS UNKNOWN.
 *      Its structure and its identity across devices are established
 *      (section 7), and two candidate roles are excluded there, but nothing in
 *      the available data indexes it. Because it is a 3-bit-to-7-line decoder,
 *      any field of exactly 3 bits could be its index -- the duration field of
 *      a control word being the obvious candidate to test first.
 *
 * 11.7 WHICH MELODY USES STACCATO ARTICULATION CANNOT BE PREDICTED.
 *      The articulation itself is fully characterised (section 8), but nothing
 *      in the note words marks the melodies that use it, so the selector lies
 *      outside the note ROM.
 *
 * 11.8 THE UM3482A RECORDINGS COME FROM A DIFFERENT MELODY MASK THAN ITS DUMP.
 *      A single continuous capture of that device holds 12 melodies, and only
 *      ONE of them can be credibly located in the ROM: slot 9, which matches
 *      note for note and yields a clean multiplier of 5 with 0.37% deviation.
 *      Two further candidate alignments exist but are rejected -- their implied
 *      multipliers are 3 with 25% deviation and 2.5 with 100% deviation, so
 *      they are pattern coincidences rather than real matches.
 *
 *      The mismatch is structural. The capture holds 479 notes while the ROM
 *      carries real data only up to index 423 inclusive, everything from 424 on being rest
 *      filler, and a word can produce at most one note. The first recorded
 *      melody alone has 52 notes against a largest slot of 38 words. The
 *      failing melodies align nowhere even when the tone table is left
 *      entirely free, so that only the pattern of which notes repeat which has
 *      to match. Because the capture is continuous and unedited, the failures
 *      cannot be an artefact of song segmentation or of joining separately
 *      recorded fragments.
 *
 *      One melody carried over and eleven replaced is what a mask revision
 *      looks like. The tone generator itself is unchanged: the device's
 *      divisor inventory is drawn from the same family pool (3b), and the six
 *      tone codes that appear in the matching melody agree with the UM3481A.
 *
 * ----------------------------------------------------------------------------
 * PER-SLOT CONFIDENCE
 * ----------------------------------------------------------------------------
 *   UM3481A slots 1-8   Highest confidence: all eight align exactly with a
 *                       recording (58, 63, 41, 42, 52, 21, 57 and 56 notes,
 *                       100% of each), the device's full tone table is
 *                       measured, and every multiplier is read directly off
 *                       the recording. Against a continuous capture of the
 *                       whole device, generated length matches to within one
 *                       millisecond for six of the eight, once the slot's own
 *                       leading rest and control word -- which the capture
 *                       begins after -- are added to the measured note span.
 *                       Slot 5 is out by 82 ms, the tail of its final staccato
 *                       pulse. Slot 5 additionally uses staccato articulation,
 *                       which is not reproduced (8, 11.7).
 *   UM3482A slot 9      The only slot on that device with a credible playback
 *                       check: exact pitch alignment and a directly measured
 *                       multiplier (11.8).
 *   UM3482A slots       Marked "~" in the run output: these contain at least
 *   2,4,5,6,8,10,15,16  one tone code whose frequency is estimated rather than
 *                       measured (11.1). Pitch contour is right in outline but
 *                       individual notes may be wrong.
 *   All other UM3482A   Decoded with the same tables, but that device's
 *   slots               recordings come from a different melody mask (11.8),
 *                       so none has a direct playback check and none has a
 *                       measured multiplier. Their pitch and relative rhythm
 *                       follow rules verified elsewhere on the family; their
 *                       absolute speed does not.
 *
 * ----------------------------------------------------------------------------
 * USAGE
 * ----------------------------------------------------------------------------
 *   mvn package
 *   java -cp target/classes org.recreativas.mame.UM348xDecoder [inputDir] [outputDir]
 *
 * or without Maven:
 *
 *   javac -d target/classes src/main/java/org/recreativas/mame/UM348xDecoder.java
 *   java -cp target/classes org.recreativas.mame.UM348xDecoder [inputDir] [outputDir]
 *
 * Both directories are optional (default: current directory, and "output").
 * The input directory is scanned for either or both devices' ROM sets; each
 * device found is decoded and its melodies written as &lt;chip&gt;_melody_NN.wav.
 * Recognised note-ROM names are um3481araw.bin and um3482araw.bin; offsets and
 * tempos are located by matching prefix, falling back to the bare names
 * offsets.bin / tempos.bin.
 * ============================================================================
 */
public class UM348xDecoder {

    /** Utility class: not instantiable. */
    private UM348xDecoder() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Section 1: physical note-ROM geometry.
    // ------------------------------------------------------------------
    static final int ROM_ROWS = 64;
    static final int ROM_GROUPS = 7;
    static final int SUBCOLUMNS = 8;

    /**
     * Logical melody order visits the physical sub-columns in this order, not
     * 0..7: the second half of the array is traversed in reverse. See
     * section 1.
     */
    static final int[] SUBCOLUMN_ORDER = {0, 1, 2, 3, 7, 6, 5, 4};
    static final int TOTAL_NOTES = ROM_ROWS * SUBCOLUMNS; // 512
    static final int EXPECTED_ROM_BYTES = ROM_ROWS * ROM_GROUPS; // 448

    static final int REST_TONE_RAW = 3;

    /**
     * Raw tone code 1 is a CONTROL word, not a note: it produces no sound.
     * Evidence (section 3a): in every passage that aligns exactly with a
     * recording, the first audible note corresponds to the ROM word AFTER the
     * tone-1 word, never to the tone-1 word itself -- confirmed independently
     * on UM3481A slots 1 and 4 and on UM3482A slot 7. It is treated here as a
     * rest: silent, but still consuming its word's duration.
     */
    static final int CONTROL_TONE_RAW = 1;

    // ------------------------------------------------------------------
    // Section 3: tone code -> oscillation period, in samples at the 100 kHz
    // rate of the reference captures. The two devices DO NOT share one table
    // (see section 3), so each has its own. -1 marks a code with no measured
    // value; 0 marks a code that is not a sounding note.
    // ------------------------------------------------------------------
    static final int CAPTURE_RATE_HZ = 100000;

    /** UM3481A: all 14 sounding codes present in its ROM measured directly. */
    static final int[] PERIODS_UM3481A = {
        202,  // 0   495.05 Hz
          0,  // 1   control word, silent
        142,  // 2   704.23 Hz
          0,  // 3   rest
        170,  // 4   588.24 Hz
         96,  // 5  1041.67 Hz
        114,  // 6   877.19 Hz
         72,  // 7  1388.89 Hz
        190,  // 8   526.32 Hz
        106,  // 9   943.40 Hz
        126,  // 10  793.65 Hz
         76,  // 11 1315.79 Hz
        150,  // 12  666.67 Hz
         84,  // 13 1190.48 Hz
        100,  // 14 1000.00 Hz
         90   // 15 1111.11 Hz
    };

    /**
     * UM3482A: seven codes measured directly; five estimated (see below).
     * Codes 0 and 5 never occur in this chip's ROM.
     */
    static final int[] PERIODS_UM3482A = {
         -1,  // 0   never occurs in this ROM
          0,  // 1   control word, silent
        142,  // 2   704.23 Hz  measured (n=13)
          0,  // 3   rest
        170,  // 4   588.24 Hz  measured (n= 8)
         -1,  // 5   never occurs in this ROM
        114,  // 6   877.19 Hz  measured (n= 4)
         84,  // 7  1190.48 Hz  ESTIMATED, see 11.1
        190,  // 8   526.32 Hz  measured (n=11)
        134,  // 9   746.27 Hz  ESTIMATED, see 11.1
        126,  // 10  793.65 Hz  measured (n=15)
        254,  // 11  393.70 Hz  measured (n= 2)  <-- low, unlike UM3481A's 76
        150,  // 12  666.67 Hz  measured (n=15)
         96,  // 13 1041.67 Hz  ESTIMATED, see 11.1
        106,  // 14  943.40 Hz  ESTIMATED, see 11.1
        100   // 15 1000.00 Hz  ESTIMATED, see 11.1
    };

    /** Codes whose period is an estimate rather than a measurement, per chip. */
    static final Set<Integer> ESTIMATED_UM3482A = new HashSet<>(Arrays.asList(
            Integer.valueOf(7), Integer.valueOf(9), Integer.valueOf(13),
            Integer.valueOf(14), Integer.valueOf(15)));

    static int[] periodTableFor(final String chip) {
        return "UM3481A".equals(chip) ? PERIODS_UM3481A : PERIODS_UM3482A; //$NON-NLS-1$
    }

    static double frequencyForRawTone(final String chip, final int rawTone) {
        final var period = periodTableFor(chip)[rawTone];
        if (period > 0) {
			return (double) CAPTURE_RATE_HZ / period;
		}
        return 0.0; // silent (rest/control) or a code this chip never uses
    }

    // ------------------------------------------------------------------
    // Section 4: measured duration code -> ticks (smallest duration = 1).
    // ------------------------------------------------------------------
    static final int[] DURATION_TICKS = {
         2,  // 0  counted directly, 24 notes
         3,  // 1  not measured, see 11.4
        15,  // 2  counted directly, 4 notes
         4,  // 3  counted directly, 24 notes
         1,  // 4  not measured, see 11.4
         8,  // 5  from duration ratios
        12,  // 6  counted directly, in a run
         6   // 7  from duration ratios
    };

    static int ticksForDuration(final int durationCode) {
        return DURATION_TICKS[durationCode & 0x7];
    }

    // ------------------------------------------------------------------
    // Section 6: tempo. Tick length = multiplier * TEMPO_BASE_UNIT_MS.
    // Multipliers measured directly are listed per device and slot; anything
    // absent falls back to the most commonly observed value. The multiplier
    // is not derivable from the tempo byte -- see 11.5.
    // ------------------------------------------------------------------
    static final double TEMPO_BASE_UNIT_MS = 20.48; // 2048 oscillator cycles
    static final int DEFAULT_TEMPO_MULTIPLIER = 5;

    /**
     * The first word of a slot, when it is a rest, always lasts exactly 8 base
     * units regardless of the slot's multiplier or the word's duration code.
     * Measured at all seven melody boundaries of the UM3481A (section 6a).
     */
    static final double FIRST_REST_BASE_UNITS = 8.0;

    static final Map<String, Integer> TEMPO_MULTIPLIER_OVERRIDES = new HashMap<>();
    static {
        // UM3481A: every non-staccato melody aligns exactly with its slot, so
        // each multiplier below is read straight off the recording.
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:1", Integer.valueOf(5)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:2", Integer.valueOf(4)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:3", Integer.valueOf(6)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:4", Integer.valueOf(3)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:5", Integer.valueOf(4));   // staccato melody, see section 8 //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:6", Integer.valueOf(5)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:7", Integer.valueOf(4)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:8", Integer.valueOf(6)); //$NON-NLS-1$
        // UM3482A: slot 9 is the only one whose alignment is clean enough to
        // measure (deviation 0.6%; every other candidate alignment on that
        // device yields a non-integer multiplier).
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3482A:9", Integer.valueOf(5)); //$NON-NLS-1$
    }

    static int tempoMultiplier(final String chip, final int slot1Based) {
        final var m = TEMPO_MULTIPLIER_OVERRIDES.get(chip + ":" + slot1Based); //$NON-NLS-1$
        return m != null ? m.intValue() : DEFAULT_TEMPO_MULTIPLIER;
    }

    static double noteDurationMs(final int durationCode, final int multiplier) {
        return ticksForDuration(durationCode) * multiplier * TEMPO_BASE_UNIT_MS;
    }

    // ------------------------------------------------------------------
    // Section 8: synthesis.
    // ------------------------------------------------------------------
    static final int SAMPLE_RATE = 44100;
    static final double AMPLITUDE = 0.60 * Short.MAX_VALUE;

    /** One device's ROM set. */
    static class Chip {
        String name;
        int[][] notes;   // [512][2] = {durationCode, toneCode}
        int[] offsets;   // slot start note indices
        int[] tempos;    // raw tempo bytes
        int songCount;   // number of real melodies
        int dataEnd;     // index of the last word that can sound; the rest is filler
    }

    /**
     * Entry point. Decodes every UM348x ROM set found in the input directory
     * and writes one WAV per melody.
     *
     * @param args Optional: input directory (default the current one) and
     *             output directory (default "output").
     * @throws IOException if a ROM cannot be read or a WAV cannot be written.
     */
    public static void main(final String[] args) throws IOException {
        System.setOut(new PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));

        final var inputDir = args.length > 0 ? args[0] : "."; //$NON-NLS-1$
        final var outputDir = args.length > 1 ? args[1] : "output"; //$NON-NLS-1$

        final List<Chip> chips = new ArrayList<>();
        final Set<Path> claimed = new HashSet<>();
        final var c1 = loadChip(inputDir, "UM3481A", "um3481araw.bin", "um3481a_offsets.bin", "um3481a_tempos.bin", claimed); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (c1 != null) {
			chips.add(c1);
		}
        final var c2 = loadChip(inputDir, "UM3482A", "um3482araw.bin", "um3482a_offsets.bin", "um3482a_tempos.bin", claimed); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (c2 != null) {
			chips.add(c2);
		}

        if (chips.isEmpty()) {
            System.err.println("No chip ROM set found in " + Paths.get(inputDir).toAbsolutePath()); //$NON-NLS-1$
            System.err.println("Expected um3481araw.bin and/or um3482araw.bin plus their offsets/tempos files."); //$NON-NLS-1$
            System.exit(1);
        }

        Files.createDirectories(Paths.get(outputDir));

        for (final Chip chip : chips) {
            System.out.println("=== " + chip.name + " ==="); //$NON-NLS-1$ //$NON-NLS-2$
            System.out.println("Notes decoded: " + TOTAL_NOTES //$NON-NLS-1$
                    + "   slot pointers: " + chip.offsets.length //$NON-NLS-1$
                    + "   melodies: " + chip.songCount); //$NON-NLS-1$
            System.out.println();
            System.out.printf(Locale.ROOT, "%-4s %-7s %-7s %-7s %-7s %-6s %-10s %-8s %-4s%n", //$NON-NLS-1$
                    "#", "start", "end", "words", "tempo", "mult", "duration", "notes", "est"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$

            for (var slot = 0; slot < chip.songCount; slot++) {
                final var start = chip.offsets[slot];
                var end = slot + 1 < chip.offsets.length
                        ? chip.offsets[slot + 1] - 1 : TOTAL_NOTES - 1;
                if (end >= TOTAL_NOTES) {
                    end = TOTAL_NOTES - 1;
                }
                // Stop at the last word that can sound, so the trailing filler
                // is not rendered as minutes of silence. Rests that sit before
                // that point are part of the melody and are kept.
                if (end > chip.dataEnd) {
                    end = chip.dataEnd;
                }
                if (end < start) {
                    end = start;
                }

                final var mult = tempoMultiplier(chip.name, slot + 1);
                final var tempoByte = slot < chip.tempos.length ? chip.tempos[slot] : -1;

                final var audio = synthesizeSong(chip.name, chip.notes, start, end, mult);
                final var melodic = countMelodic(chip.notes, start, end);

                final var filename = String.format(Locale.ROOT, "%s_melody_%02d.wav", chip.name.toLowerCase(), Integer.valueOf(slot + 1)); //$NON-NLS-1$
                writeWav(Paths.get(outputDir, filename).toString(), audio, SAMPLE_RATE);

                final var measured = TEMPO_MULTIPLIER_OVERRIDES.containsKey(chip.name + ":" + (slot + 1)); //$NON-NLS-1$
                final var estTone = slotUsesEstimatedTone(chip.name, chip.notes, start, end);
                System.out.printf(Locale.ROOT, "%-4d %-7d %-7d %-7d %-7d %-6s %7.2f s  %-8d %-4s -> %s%n", //$NON-NLS-1$
                		Integer.valueOf(slot + 1), Integer.valueOf(start), Integer.valueOf(end), Integer.valueOf(end - start + 1), Integer.valueOf(tempoByte),
                        mult + (measured ? "*" : ""), //$NON-NLS-1$ //$NON-NLS-2$
                        Double.valueOf(audio.length / (double) SAMPLE_RATE), Integer.valueOf(melodic),
                        estTone ? "~" : "", filename); //$NON-NLS-1$ //$NON-NLS-2$
            }
            System.out.println("  * tempo multiplier measured directly; the rest use the fallback of " //$NON-NLS-1$
                    + DEFAULT_TEMPO_MULTIPLIER + " (section 6)"); //$NON-NLS-1$
            if ("UM3482A".equals(chip.name)) { //$NON-NLS-1$
                System.out.println("  ~ slot contains a tone code whose frequency is estimated, " //$NON-NLS-1$
                        + "not measured (see 11.1)"); //$NON-NLS-1$
            }
            System.out.println();
        }

        System.out.println("Done. WAV files written to: " + Paths.get(outputDir).toAbsolutePath()); //$NON-NLS-1$
    }

    // ========================================================================
    // LOADING
    // ========================================================================

    static Chip loadChip(final String dir, final String name, final String romFile,
                         final String offsetsFile, final String temposFile,
                         final Set<Path> claimed) throws IOException {
        final var rom = Paths.get(dir, romFile);
        if (!Files.exists(rom)) {
            return null;
        }

        // The bare names are a fallback for dumps that predate the per-part
        // naming. Refuse one that another part has already taken, since the
        // same offsets or tempos cannot belong to two different devices.
        final var off = resolve(dir, offsetsFile, "offsets.bin", name, claimed); //$NON-NLS-1$
        final var tmp = resolve(dir, temposFile, "tempos.bin", name, claimed); //$NON-NLS-1$
        if (off == null || tmp == null) {
            System.err.println("Skipping " + name + ": no usable offsets/tempos file alongside " + romFile); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        final var romBytes = Files.readAllBytes(rom);
        if (romBytes.length != EXPECTED_ROM_BYTES) {
            System.err.println("Skipping " + name + ": " + romFile + " is " + romBytes.length //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " bytes; the note ROM must be exactly " + EXPECTED_ROM_BYTES + "."); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        final int[] offsets;
        try {
            offsets = parseOffsets(Files.readAllBytes(off));
            validateOffsets(offsets);
        }
        catch (final IllegalArgumentException e) {
            System.err.println("Skipping " + name + ": " + off.getFileName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }

        final var c = new Chip();
        c.name = name;
        c.notes = decodeNotes(romBytes);
        c.offsets = offsets;
        c.tempos = parseTempos(Files.readAllBytes(tmp));
        c.dataEnd = lastSoundingWord(c.notes);
        c.songCount = countRealSongs(c.offsets, c.dataEnd);

        if (c.songCount == 0) {
            System.err.println("Skipping " + name + ": no melody pointer addresses any sounding word."); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        warnUnknownTones(c);
        return c;
    }

    /**
     * Pick the per-part file, falling back to the bare name only if no other
     * part has claimed it.
     *
     * @param dir Directory to look in.
     * @param preferred Per-part file name.
     * @param fallback Bare file name kept for older dumps.
     * @param chip Part being loaded, used for messages.
     * @param claimed Files already taken by another part.
     * @return The file to read, or null if none is usable.
     */
    static Path resolve(final String dir, final String preferred, final String fallback,
                        final String chip, final Set<Path> claimed) {
        final var first = Paths.get(dir, preferred);
        if (Files.exists(first)) {
            claimed.add(first.toAbsolutePath().normalize());
            return first;
        }

        final var second = Paths.get(dir, fallback).toAbsolutePath().normalize();
        if (!Files.exists(second)) {
            return null;
        }
        if (!claimed.add(second)) {
            System.err.println("Not using " + fallback + " for " + chip //$NON-NLS-1$ //$NON-NLS-2$
                    + ": another part already used it. Rename it to " + preferred + "."); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        System.err.println("Note: " + chip + " is using the generic " + fallback //$NON-NLS-1$ //$NON-NLS-2$
                + "; rename it to " + preferred + " to be unambiguous."); //$NON-NLS-1$ //$NON-NLS-2$
        return Paths.get(dir, fallback);
    }

    /**
     * Report tone codes that occur in this part's melodies but have no entry
     * in its table, so that they are not silently dropped.
     *
     * @param c Chip to check.
     */
    static void warnUnknownTones(final Chip c) {
        final var table = periodTableFor(c.name);
        final var seen = new TreeSet<Integer>();
        final var end = Math.min(c.dataEnd, TOTAL_NOTES - 1);
        for (var i = 0; i <= end; i++) {
            final var tone = c.notes[i][1];
            if (tone != REST_TONE_RAW && tone != CONTROL_TONE_RAW && table[tone] <= 0) {
                seen.add(Integer.valueOf(tone));
            }
        }
        if (!seen.isEmpty()) {
            System.err.println("Warning: " + c.name + " uses tone codes with no table entry: " //$NON-NLS-1$ //$NON-NLS-2$
                    + seen + ". Those notes will be silent."); //$NON-NLS-1$
        }
    }

    /**
     * Offset packing is detected by file size (section 2): 24 bytes means 16
     * entries of 12 packed bits, 32 bytes means 16 big-endian 16-bit words.
     */
    static int[] parseOffsets(final byte[] data) {
        if (data.length != 24 && data.length != 32) {
            throw new IllegalArgumentException("offsets file is " + data.length //$NON-NLS-1$
                    + " bytes; expected 24 (12-bit packed) or 32 (16-bit big-endian)"); //$NON-NLS-1$
        }
        if (data.length == 24) {
            final var entries = data.length * 8 / 12;
            final var out = new int[entries];
            for (var i = 0; i < entries; i++) {
                final var bitPos = i * 12;
                var v = 0;
                for (var b = 0; b < 12; b++) {
                    final var idx = bitPos + b;
                    final var bit = data[idx >> 3] >> 7 - (idx & 7) & 1;
                    v = v << 1 | bit;
                }
                out[i] = v;
            }
            return out;
        }
        final var n = data.length / 2;
        final var out = new int[n];
        for (var i = 0; i < n; i++) {
            out[i] = (data[i * 2] & 0xFF) << 8 | data[i * 2 + 1] & 0xFF;
        }
        return out;
    }

    /**
     * Reject pointer tables that cannot describe a melody layout: the first
     * pointer must be in range, and the leading run must not decrease.
     *
     * @param offsets Parsed pointers.
     * @throws IllegalArgumentException if the table is unusable.
     */
    static void validateOffsets(final int[] offsets) {
        if (offsets.length == 0) {
            throw new IllegalArgumentException("offsets table is empty"); //$NON-NLS-1$
        }
        if (offsets[0] < 0 || offsets[0] >= TOTAL_NOTES) {
            throw new IllegalArgumentException("first melody pointer is " + offsets[0] //$NON-NLS-1$
                    + ", outside 0.." + (TOTAL_NOTES - 1)); //$NON-NLS-1$
        }
    }

    static int[] parseTempos(final byte[] data) {
        final var out = new int[data.length];
        for (var i = 0; i < data.length; i++) {
			out[i] = data[i] & 0xFF;
		}
        return out;
    }

    /**
     * Real melodies are the strictly increasing leading pointers; the trailing
     * repeated filler value marks unused slots (section 2).
     */
    static int countRealSongs(final int[] offsets, final int dataEnd) {
        if (offsets.length == 0) {
            return 0;
        }
        var n = 1;
        while (n < offsets.length && offsets[n] > offsets[n - 1] && offsets[n] < TOTAL_NOTES) {
            n++;
        }
        // Pointers past the last sounding word address nothing but filler,
        // whether or not the filler value happens to repeat.
        while (n > 0 && offsets[n - 1] > dataEnd) {
            n--;
        }
        return n;
    }

    /**
     * Index of the last word that can sound. Everything after it is filler:
     * the UM3482A dump ends in 88 rest words, the UM3481A in 13.
     *
     * @param notes Decoded note words.
     * @return Index of the last sounding word, or -1 if there is none.
     */
    static int lastSoundingWord(final int[][] notes) {
        for (var i = notes.length - 1; i >= 0; i--) {
            final var tone = notes[i][1];
            if (tone != REST_TONE_RAW && tone != CONTROL_TONE_RAW) {
                return i;
            }
        }
        return -1;
    }

    // ========================================================================
    // NOTE-ROM DECODING  (sections 1-2)
    // ========================================================================

    static int[][] decodeNotes(final byte[] rom) {
        final var notes = new int[TOTAL_NOTES][2];
        for (var logical = 0; logical < SUBCOLUMNS; logical++) {
            final var s = SUBCOLUMN_ORDER[logical];   // physical sub-column
            for (var r = 0; r < ROM_ROWS; r++) {
                var word = 0;
                for (var g = 0; g < ROM_GROUPS; g++) {
                    final var byteIndex = r * ROM_GROUPS + g;
                    final var b = byteIndex < rom.length ? rom[byteIndex] & 0xFF : 0;
                    word = word << 1 | b >> s & 1;
                }
                notes[logical * ROM_ROWS + r][0] = word >> 4 & 0x7; // duration
                notes[logical * ROM_ROWS + r][1] = word & 0xF;         // tone
            }
        }
        return notes;
    }

    static int countMelodic(final int[][] notes, final int start, final int end) {
        var count = 0;
        for (var i = start; i <= end; i++) {
            final var t = notes[i][1];
            if (t != REST_TONE_RAW && t != CONTROL_TONE_RAW) {
				count++;
			}
        }
        return count;
    }

    /** Slots containing a tone code whose frequency is estimated, not measured. */
    static boolean slotUsesEstimatedTone(final String chip, final int[][] notes, final int start, final int end) {
        if (!"UM3482A".equals(chip)) { //$NON-NLS-1$
			return false;
		}
        for (var i = start; i <= end; i++) {
            if (ESTIMATED_UM3482A.contains(Integer.valueOf(notes[i][1]))) {
				return true;
			}
        }
        return false;
    }

    // ========================================================================
    // SYNTHESIS  (sections 5 and 8)
    // ========================================================================

    static short[] synthesizeSong(final String chip, final int[][] notes, final int start, final int end, final int multiplier) {
        final List<short[]> chunks = new ArrayList<>();
        var total = 0;
        var sampleCounter = 0L; // continuous across the song, see section 5

        for (var i = start; i <= end; i++) {
            var ms = noteDurationMs(notes[i][0], multiplier);
            final var toneRaw = notes[i][1];
            if (i == start && toneRaw == REST_TONE_RAW) {
                ms = FIRST_REST_BASE_UNITS * TEMPO_BASE_UNIT_MS; // section 6a
            }

            // Rests and the tone-1 control word are silent but still take time.
            final var freq = toneRaw == REST_TONE_RAW || toneRaw == CONTROL_TONE_RAW
                    ? 0.0 : frequencyForRawTone(chip, toneRaw);

            final var chunk = freq <= 0.0
                    ? new short[msToSamples(ms)]
                    : synthesizeNote(freq, ms, sampleCounter);
            chunks.add(chunk);
            total += chunk.length;
            sampleCounter += chunk.length;
        }

        final var full = new short[total];
        var pos = 0;
        for (final short[] c : chunks) { System.arraycopy(c, 0, full, pos, c.length); pos += c.length; }
        return full;
    }

    static int msToSamples(final double ms) {
        return (int) Math.round(ms / 1000.0 * SAMPLE_RATE);
    }

    /**
     * 50%-duty square wave with phase taken from a song-wide sample counter,
     * so that consecutive words of equal pitch join seamlessly (section 5).
     */
    static short[] synthesizeNote(final double freqHz, final double durationMs, final long phaseOffset) {
        final var totalSamples = msToSamples(durationMs);
        final var samples = new short[Math.max(totalSamples, 0)];
        if (totalSamples <= 0 || freqHz <= 0.0) {
			return samples;
		}

        final var period = SAMPLE_RATE / freqHz;
        for (var i = 0; i < totalSamples; i++) {
            final var phase = (phaseOffset + i) % period / period;
            samples[i] = (short) Math.round((phase < 0.5 ? 1.0 : -1.0) * AMPLITUDE);
        }
        return samples;
    }

    // ========================================================================
    // WAV OUTPUT  (PCM, 16-bit, mono)
    // ========================================================================

    static void writeWav(final String path, final short[] samples, final int sampleRate) throws IOException {
        final var dataSize = samples.length * 2;
        try (var out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(path)))) {
            out.writeBytes("RIFF"); //$NON-NLS-1$
            writeLE32(out, 36 + dataSize);
            out.writeBytes("WAVE"); //$NON-NLS-1$
            out.writeBytes("fmt "); //$NON-NLS-1$
            writeLE32(out, 16);
            writeLE16(out, 1);
            writeLE16(out, 1);
            writeLE32(out, sampleRate);
            writeLE32(out, sampleRate * 2);
            writeLE16(out, 2);
            writeLE16(out, 16);
            out.writeBytes("data"); //$NON-NLS-1$
            writeLE32(out, dataSize);
            for (final short s : samples) {
				writeLE16(out, s & 0xFFFF);
			}
        }
    }

    static void writeLE32(final DataOutputStream out, final int value) throws IOException {
        out.write(value & 0xFF); out.write(value >> 8 & 0xFF);
        out.write(value >> 16 & 0xFF); out.write(value >> 24 & 0xFF);
    }

    static void writeLE16(final DataOutputStream out, final int value) throws IOException {
        out.write(value & 0xFF); out.write(value >> 8 & 0xFF);
    }
}
