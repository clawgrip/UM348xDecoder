package org.recreativas.mame;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ============================================================================
 *  UM348xDecoder
 *  Reverse-engineered ROM decoder and WAV synthesizer for the UM348x family
 *  of melody-generator ICs. Verified against two devices: the UM3481A
 *  (8 melodies) and the UM3482A (12 melodies).
 *
 *  Based on previous work from:
 *   - Sean Riddle: https://www.seanriddle.com/um348x/
 *   - ArcadeHacker: https://arcadehacker.blogspot.com/2020/07/um3481a-series-multi-instrument-melody.html
 * ============================================================================
 *
 * INPUT
 * -----
 * Per device, three raw ROM dumps:
 *
 *   <chip>raw.bin     (448 bytes)  Main note ROM. 448 bytes = 3584 bits =
 *                                  64 rows x 56 columns, matching the physical
 *                                  cell array reported from die inspection of
 *                                  this family: 7 groups of 8 columns.
 *
 *   <chip>offsets.bin              Melody start pointers, one per addressable
 *                                  slot (see section 2 for the two packings
 *                                  observed).
 *
 *   <chip>tempos.bin  (16 bytes)   One byte per slot, from the ROM bank
 *                                  conventionally described as tempo. Its
 *                                  actual role is not established -- see
 *                                  sections 6 and 9.
 *
 * No manufacturer documentation of the internal note/duration/tempo encoding
 * is published, so every parameter here was derived from the chips' own data
 * and calibrated against logic-level captures of real playback. Sections 1-8
 * describe what the data establishes; section 9 collects everything that
 * remains unknown, uncertain or unmodelled.
 *
 * Ground truth: four logic-level captures at 100 kHz -- two covering all 12
 * UM3482A melodies between them, and two covering 8 UM3481A melodies. All are
 * two-level signals (the raw square wave driving the speaker pin), which
 * allows sample-exact period and duration measurement. Methodology in
 * section 7.
 *
 * ----------------------------------------------------------------------------
 * 1. PHYSICAL NOTE-ROM ADDRESSING
 * ----------------------------------------------------------------------------
 * The 448-byte note ROM is read row-major: row r (0-63), 7 bytes per row (one
 * per physical column-group g, 0-6). Each byte holds that group's bit for the
 * 8 "sub-columns" sharing the row; sub-column s (0-7) is bit s of the byte
 * (s = 0 -> least-significant).
 *
 * Melodies do NOT run through the sub-columns in the order 0..7. The logical
 * order is:
 *
 *     0, 1, 2, 3, 7, 6, 5, 4          (SUBCOLUMN_ORDER)
 *
 * i.e. the second half of the array is traversed in reverse, so:
 *
 *     noteIndex = position_in_SUBCOLUMN_ORDER * 64 + row      (0..511)
 *
 * Three independent lines of evidence fix this order:
 *
 *   - Melody continuity. Aligning a whole UM3481A recording against the ROM as
 *     one uninterrupted stream, the match runs perfectly for 190 notes and
 *     then stops dead at logical index 256 -- exactly a sub-column boundary
 *     (4 x 64). The notes that follow are found at physical sub-column 7, not
 *     4. Fixing the order to 0,1,2,3,7,6,5,4 makes every non-staccato melody
 *     in both UM3481A recordings align exactly with its slot: 58, 63, 41, 42,
 *     21, 57 and 56 notes, 100% of them, in slot order.
 *   - Slot pointers land correctly. A second UM3481A capture, holding that
 *     device's last melodies, aligns 134 of 134 notes starting precisely at
 *     ROM index 329, which is offsets[5] -- the start of slot 6.
 *   - UM3482A slot occupancy becomes sane. Under the naive 0..7 order that
 *     device has two slots containing nothing but rests and one slot of 97
 *     melodic words, three times any other. Under the correct order no slot is
 *     empty and slot sizes run a uniform 14 to 38 words.
 *
 * ----------------------------------------------------------------------------
 * 2. NOTE WORD FORMAT AND SLOT POINTERS
 * ----------------------------------------------------------------------------
 * Each note is a 7-bit word read in the bit order the physical layout already
 * provides (group 0 = MSB, group 6 = LSB; no group permutation needed):
 *
 *     bits 6-4 (3 bits)  ->  DURATION  (raw code 0-7)
 *     bits 3-0 (4 bits)  ->  TONE      (raw code 0-15)
 *
 * Tone code 3 is silence/rest on both devices: it opens most melody slots and
 * fills unused slots entirely. Tone code 1 is a silent control word
 * (section 3a). The other 14 codes are sounding notes.
 *
 * The two offset dumps use different bit packings, detected by file size:
 *
 *   32 bytes -> 16 entries of 16 bits, big-endian       (UM3482A dump)
 *   24 bytes -> 16 entries of 12 bits, packed MSB-first (UM3481A dump)
 *
 * The 12-bit reading is confirmed by its output: 0, 88, 165, 213, 271, 329,
 * 358, 421 followed by 499 repeated eight times -- eight strictly increasing
 * pointers for a chip with eight melodies, then a repeated filler value. No
 * other packing produces a sane result.
 *
 * This family is often described as offering 14 selectable tones, and on the
 * UM3482A alone that appears to hold, since tone codes 0 and 5 never occur in
 * its ROM. The UM3481A rules out any structural reading of this: it uses code
 * 5 heavily (61 occurrences) and code 0 once. Across both devices all 16 codes
 * occur, so codes 0 and 5 are simply unused by one song set, not reserved by
 * the hardware.
 *
 * ----------------------------------------------------------------------------
 * 3. TONE -> FREQUENCY  (per-device lookup tables; the devices DIFFER)
 * ----------------------------------------------------------------------------
 * The mapping is a direct lookup, not a computable scale formula, and it is
 * not shared between the two devices. Each has its own table here.
 *
 * UM3481A -- all 14 sounding codes present in its ROM are measured, from four
 * melodies that align note-for-note with recordings, with no conflicting
 * readings anywhere:
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
 * UM3482A -- seven codes measured, from three exactly-aligned melodies. Six
 * agree with the UM3481A values, but code 11 does not, and the disagreement is
 * large and unambiguous:
 *
 *     code 11 ->  254 samples =  393.70 Hz on the UM3482A
 *     code 11 ->   76 samples = 1315.79 Hz on the UM3481A
 *
 * Both readings come from exact alignments (2 observations on the UM3482A, 5
 * across three separate UM3481A melodies), so this is a genuine per-device
 * difference, not noise: the same code sits near the bottom of one device's
 * range and near the top of the other's. The overall ranges differ to match --
 * the UM3482A recordings never produce a period below 84 samples, while the
 * UM3481A regularly reaches 76 and 72. The UM3482A is voiced lower.
 *
 * UM3482A codes 7, 9, 13, 14 and 15 have no measured value and are estimated;
 * see section 9.1 for how and why. Codes 0 and 5 never occur in the UM3482A
 * ROM, so they need no entry.
 *
 * ----------------------------------------------------------------------------
 * 3a. TONE CODE 1 IS A CONTROL WORD, NOT A NOTE
 * ----------------------------------------------------------------------------
 * Raw tone code 1 produces no sound. In every passage that aligns exactly with
 * a recording, the first audible note corresponds to the ROM word AFTER the
 * tone-1 word, never to the tone-1 word itself. This holds independently on
 * UM3481A slots 1 and 4 and on UM3482A slot 7.
 *
 * Where these words sit is informative. On the UM3482A a rest word immediately
 * followed by a tone-1 word occurs at ROM indices 103/104, 128/129, 160/161,
 * 194/195, 344/345, 404/405, 432/433, 485/486 and 504/505. The first four are
 * slot starts, but the last five are INSIDE slots 14, 15 and 16 -- slot 16
 * alone contains four. That fits other evidence that slot 16 (104 words, by
 * far the largest) holds several melodies rather than one, and that the
 * offsets table does not delimit songs one-to-one.
 *
 * The word is treated here as a rest: silent, but still consuming its word's
 * duration. What it selects, and whether it consumes time at all, are open --
 * see sections 9.2 and 9.3.
 *
 * ----------------------------------------------------------------------------
 * 4. DURATION CODE -> TICKS
 * ----------------------------------------------------------------------------
 * Duration is a lookup too, and is not linear in the raw code. Relative
 * lengths measured from the exactly-aligned passages (standard deviation of
 * each figure under 1%), in units where the shortest observed word equals 1:
 *
 *     code 0 -> 1.000 (n=23)    code 6 -> 6.012 (n=11)
 *     code 3 -> 1.999 (n=43)    code 2 -> 7.484 (n= 2)
 *     code 7 -> 3.010 (n= 7)
 *     code 5 -> 3.995 (n= 2)
 *
 * Expressed in integers by taking the smallest duration (code 4) as 1 tick:
 *
 *     code : 0   1    2     3   4   5   6    7
 *     ticks: 2   3    15    4   1   8   12   6
 *
 * Codes 1, 2 and 4 are the uncertain entries; see section 9.4.
 *
 * ADDITIVITY -- when consecutive ROM words share a tone code they sound as one
 * continuous note (section 5), and that note's length is the plain SUM of the
 * member words' ticks. Measured runs versus predicted sums:
 *
 *     run (3,3)   measured 3.997  predicted 3.999
 *     run (3,3,3) measured 5.990  predicted 5.998
 *     run (3,5)   measured 5.990  predicted 5.994
 *     run (5,3)   measured 6.015  predicted 5.994
 *
 * Because notes are rendered back to back with no gap, concatenating each
 * word's independently computed duration already performs this summation, so
 * no special merging logic is needed for timing.
 *
 * ----------------------------------------------------------------------------
 * 5. CONSECUTIVE REPEATED-TONE WORDS
 * ----------------------------------------------------------------------------
 * A run of consecutive ROM words carrying the same tone code may be rendered
 * either as one sustained note or as several re-articulated ones -- alignment
 * against the recordings requires allowing both, and a melody generally
 * cannot be matched at all if runs are forced to collapse completely.
 *
 * This distinction cannot be settled from the captures available, for a
 * mundane reason: the edge-extraction step used to read them merges any
 * uninterrupted stretch of one period into a single note event, so a genuine
 * re-articulation of the same pitch with no intervening silence is
 * indistinguishable from one long note. Any apparent absence of same-pitch
 * neighbours in the extracted data is an artefact of that step, not a
 * property of the device.
 *
 * Fortunately this does not affect synthesis. Whichever way a run is voiced,
 * its total length is the sum of the member words' ticks (section 4), and
 * notes are rendered back to back with no gap, so simply emitting each word in
 * turn produces the correct total duration either way. What does matter is
 * waveform CONTINUITY: if each word restarted its square wave at phase zero, a
 * run of equal-pitch words would acquire a discontinuity at every internal
 * boundary. A single song-wide sample counter supplies the phase instead, so
 * such runs render smoothly while genuine pitch changes still switch abruptly,
 * as the captures show.
 *
 * ----------------------------------------------------------------------------
 * 6. TEMPO
 * ----------------------------------------------------------------------------
 * Tick length is always an integer multiple of a base unit of 20.48 ms (2048
 * samples at 100 kHz, i.e. 2^11). Four multipliers are observed across the two
 * devices, each pinned to a specific slot:
 *
 *     slot            tempo byte    multiplier    tick
 *     UM3481A slot 1      72            5        102.40 ms
 *     UM3481A slot 2      44            4         81.92 ms
 *     UM3481A slot 3      41            6        122.88 ms
 *     UM3481A slot 4      80            3         61.44 ms
 *     UM3481A slot 6      36            5        102.40 ms
 *     UM3481A slot 7      54            4         81.92 ms
 *     UM3481A slot 8      91            6        122.88 ms
 *     UM3482A slot 9     126            5        102.40 ms
 *     UM3482A slot 11     88            3         61.44 ms
 *
 * Each figure is the median of the per-note ratio between measured length and
 * predicted ticks across a fully aligned melody, and every one lands within
 * 0.1% of an integer.
 *
 * Multipliers measured this way are applied per slot (see
 * TEMPO_MULTIPLIER_OVERRIDES); every other slot uses 5, the most frequently
 * observed value. Which multiplier a slot uses cannot currently be derived
 * from the ROM -- see section 9.5.
 *
 * ----------------------------------------------------------------------------
 * 7. VALIDATION METHODOLOGY
 * ----------------------------------------------------------------------------
 *   a. Each capture is binarized (two levels only) and rising edges are
 *      extracted; runs of equal cycle period become note events, long
 *      non-oscillating stretches become rests. Long rests split each capture
 *      into individual melodies.
 *   b. Melodies are located in the ROM without presupposing slot boundaries.
 *      Two search modes are available. A canonical form (each distinct value
 *      renumbered by order of first appearance) compared by longest-common-
 *      subsequence needs no frequency table, so it can bootstrap one. Given a
 *      table, an exact search is possible instead: convert measured periods to
 *      tone codes and find ROM windows matching the pattern
 *      "code1+ code2+ ... codeN+", which both localizes the melody and
 *      recovers the exact word-to-note grouping.
 *   c. Every figure quoted in sections 3-6 comes from the exact search, which
 *      yields three UM3481A melodies at 57/59, 63/64 and 41/41 notes and the
 *      UM3482A slot 9 at 24/24, all with zero contradicting readings.
 *
 * A caution that governs how this evidence must be read: LCS alignment
 * guarantees the compared PATTERNS match but not that individual positions
 * correspond, when a melody revisits a pitch class. LCS-only readings can
 * therefore produce internally inconsistent frequency assignments, and are not
 * a sound basis for a lookup table. Only exact matches are used for that.
 *
 * ----------------------------------------------------------------------------
 * 8. AUDIO SYNTHESIS
 * ----------------------------------------------------------------------------
 * A pure two-level square wave, no envelope, no inter-note gap, with an
 * immediate frequency change at note boundaries (a single transitional cycle
 * is visible when the divider ratio changes). Phase runs continuously across
 * each song, per section 5.
 *
 * ----------------------------------------------------------------------------
 * 9. OPEN QUESTIONS, UNCERTAINTIES AND UNMODELLED BEHAVIOUR
 * ----------------------------------------------------------------------------
 * Everything below is either unknown, resting on thin evidence, or known to be
 * present in the hardware but deliberately not reproduced.
 *
 * 9.1  UM3482A TONE CODES 7, 9, 13, 14, 15 ARE ESTIMATED, NOT MEASURED.
 *      None appears in a UM3482A passage that could be aligned exactly. They
 *      are not given the UM3481A values, because code 11 demonstrates the two
 *      devices' tables can differ drastically. Each is instead assigned a
 *      period the UM3482A is actually observed to produce, preserving the
 *      relative pitch order those codes have on the UM3481A. The only firm
 *      property of this estimate is that it cannot emit a pitch the device is
 *      never seen to make; the individual assignments may well be wrong.
 *      Affected slots are flagged "~" in the run output.
 *
 * 9.2  WHAT THE TONE-1 CONTROL WORD SELECTS IS UNKNOWN.
 *      Candidates consistent with the data include song-start marking,
 *      instrument or articulation select (see 9.7) and tempo select. Its
 *      duration field varies between occurrences, which would suit a
 *      parameter-carrying word, but nothing confirms this.
 *
 * 9.3  WHETHER THE TONE-1 WORD CONSUMES TIME IS UNTESTED.
 *      It is treated as a rest of its word's duration. Every instance that
 *      could be checked against a recording lies before the first audible
 *      note, where a capture cannot show whether time passed.
 *
 * 9.4  DURATION CODES 1, 2 AND 4 ARE THE WEAK ENTRIES.
 *      Codes 1 and 4 rest on limited UM3482A observations. Code 2 rests on
 *      two observations; its measured 15 ticks breaks the otherwise tidy
 *      1,2,3,4,6,8,12 progression, in which 16 would fit. The measurement
 *      (7.484 x code 0, sd 0.003) is followed here, but with low confidence.
 *
 * 9.5  THE TEMPO MULTIPLIER CANNOT BE DERIVED FROM THE TEMPO BYTE.
 *      Nine multipliers are now measured. Bytes 72 and 36 both yield 5; bytes
 *      44 and 54 both yield 4; bytes 41 and 91 both yield 6; byte 80 yields 3.
 *      No linear, proportional, modular, bitwise or bit-reversed function of
 *      the byte fits, and pairs that agree are not related by any obvious
 *      transform.
 *
 *      There is stronger evidence that this ROM is not per-song tempo data at
 *      all: the two devices carry entirely different song sets, yet their
 *      16-byte tempo ROMs share 13 of 16 byte values, with positions 1-8
 *      identical in both (44, 41, 80, 14, 36, 54, 91, 126) and the remainder
 *      looking like the same underlying sequence shifted by an insertion. A
 *      genuine per-song tempo table for two unrelated song sets would not
 *      agree like that.
 *
 *      A different source looks more promising and is worth pursuing: the
 *      multiplier correlates with the words at the head of each slot. On the
 *      UM3481A, slots whose second word is an ordinary note take multiplier 4
 *      (slots 2 and 7); slots whose second word is a rest with duration code 1
 *      take 6 (slots 3 and 8); slots whose second word is a control word with
 *      duration code 3 take 5 (slots 1 and 6); and the one slot whose control
 *      word carries duration code 1 takes 3 (slot 4). Every UM3481A slot fits
 *      this description, but with seven data points and four outcomes it could
 *      easily be coincidence, and it has not been checked against the UM3482A.
 *
 *      Consequence: slots without a measured multiplier have correct pitch and
 *      correct relative rhythm but may play at the wrong absolute speed, by a
 *      ratio of 3/5, 4/5 or 6/5.
 *
 * 9.6  THE REAL UM3482A RECORDINGS FROM SEAN'S SITE AND ITS ROM DUMP DO NOT AGREE.
 *      Only 3 of that device's 12 recorded melodies can be located in its ROM,
 *      and the failures are not explained by the missing table entries: an
 *      exhaustive search over every injective assignment of the five
 *      unmeasured tone codes to the device's unassigned observed pitches
 *      produces no assignment that makes any further melody align. Nor is it a
 *      boundary problem -- with the tone table left entirely free, so that only
 *      the pattern of which notes repeat which has to match, those melodies
 *      still align nowhere in the ROM at all.
 *
 *      Two counts show the disagreement is structural. The recordings contain
 *      479 notes while the ROM holds 399 melodic words, and a word can only
 *      ever produce one note or be merged into one, so the recording cannot be
 *      generated by this ROM as decoded. Individually, the first recorded
 *      melody has 52 notes while the largest slot holds 38 words.
 *
 *      The likeliest explanations are that the UM3482A captures come from a
 *      different ROM revision than the dump, or that they were spliced when
 *      two melodies were removed to reduce file size, joining fragments of
 *      different melodies into what looks like one. Note that the UM3481A,
 *      whose captures were not spliced in this way, aligns perfectly.
 *
 * 9.7  STACCATO / TREMOLO ARTICULATION IS NOT REPRODUCED.
 *      One melody in each device's recordings (UM3481A melody 5, UM3482A
 *      melody 8) is rendered by the hardware with every note chopped into
 *      short pulses separated by ~72 ms of silence, roughly quadrupling the
 *      audible event count. This is presumably the "multi-instrument"
 *      capability the family is marketed with. Nothing in the 7-bit note words
 *      distinguishes these melodies, so the selector lives outside the note
 *      ROM. Those melodies render here as ordinary sustained tones.
 *
 * 9.8  THE UM3481A STACCATO MELODY (SLOT 5) IS THE ONE UNALIGNED UM3481A SONG.
 *      Every other melody on that device matches its slot exactly. Slot 5
 *      cannot be aligned because its articulation (9.7) multiplies the audible
 *      event count, so its tempo multiplier is unmeasured and it falls back to
 *      the default.
 *
 * 9.9  A PITCH-RANGE MECHANISM MAY EXIST OUTSIDE THE NOTE WORD.
 *      Across both devices 17 distinct note periods occur, while a single
 *      device's word format allows at most 14 sounding codes. Per-device
 *      tables account for this here, but whether the hardware really holds two
 *      independent tone ROMs, or one table plus an octave/range control, is
 *      not determined.
 *
 * ----------------------------------------------------------------------------
 * PER-SLOT CONFIDENCE
 * ----------------------------------------------------------------------------
 *   UM3481A slots       Highest confidence: each aligns exactly with a
 *   1,2,3,4,6,7,8       recording (58, 63, 41, 42, 21, 57 and 56 notes, all
 *                       100%), the device's full tone table is measured, and
 *                       each multiplier is read directly off the recording.
 *                       Generated length lands within 0.6-2.0% of the capture
 *                       for six of the seven, and 6.8% for the shortest.
 *   UM3481A slot 5      Pitch trustworthy (full measured tone table); tempo
 *                       multiplier unmeasured, and its staccato articulation
 *                       is not reproduced (9.7, 9.8).
 *   UM3482A slots       These align with recordings; slot 9 also has its
 *   9 and 11            multiplier measured directly.
 *   UM3482A slots       Marked "~" in the run output: these contain at least
 *   2,4,5,6,8,10,14,16  one tone code whose frequency is estimated rather than
 *                       measured (9.1). Pitch contour is right in outline but
 *                       individual notes may be wrong.
 *   All other UM3482A   Decoded with the same tables, but that device's
 *   slots               recordings cannot be reconciled with its ROM dump
 *                       (9.6), so none of them has a direct playback check and
 *                       none has a measured multiplier.
 *
 * ----------------------------------------------------------------------------
 * USAGE
 * ----------------------------------------------------------------------------
 *   javac UM348xDecoder.java
 *   java UM348xDecoder [inputDir] [outputDir]
 *
 * Both directories are optional (default: current directory, and "output").
 * The input directory is scanned for either or both devices' ROM sets; each
 * device found is decoded and its melodies written as <chip>_melody_NN.wav.
 * Recognised note-ROM names are um3481araw.bin and um3482araw.bin; offsets and
 * tempos are located by matching prefix.
 * ============================================================================
 */
public class UM348xDecoder {

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
         84,  // 7  1190.48 Hz  ESTIMATED, see 9.1
        190,  // 8   526.32 Hz  measured (n=11)
        134,  // 9   746.27 Hz  ESTIMATED, see 9.1
        126,  // 10  793.65 Hz  measured (n=15)
        254,  // 11  393.70 Hz  measured (n= 2)  <-- low, unlike UM3481A's 76
        150,  // 12  666.67 Hz  measured (n=15)
         96,  // 13 1041.67 Hz  ESTIMATED, see 9.1
        106,  // 14  943.40 Hz  ESTIMATED, see 9.1
        100   // 15 1000.00 Hz  ESTIMATED, see 9.1
    };

    /** Codes whose period is an estimate rather than a measurement, per chip. */
    static final Set<Integer> ESTIMATED_UM3482A = new HashSet<>(Arrays.asList(
		Integer.valueOf(7),
		Integer.valueOf(9),
		Integer.valueOf(13),
		Integer.valueOf(14),
		Integer.valueOf(15)
	));

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
         2,  // 0  measured
         3,  // 1  uncertain, see 9.4
        15,  // 2  measured n=2, low confidence, see 9.4
         4,  // 3  measured
         1,  // 4  uncertain, see 9.4
         8,  // 5  measured
        12,  // 6  measured
         6   // 7  measured
    };

    static int ticksForDuration(final int durationCode) {
        return DURATION_TICKS[durationCode & 0x7];
    }

    // ------------------------------------------------------------------
    // Section 6: tempo. Tick length = multiplier * TEMPO_BASE_UNIT_MS.
    // Multipliers measured directly are listed per device and slot; anything
    // absent falls back to the most commonly observed value. The multiplier
    // is not derivable from the tempo byte -- see 9.5.
    // ------------------------------------------------------------------
    static final double TEMPO_BASE_UNIT_MS = 20.48; // 2048 samples @ 100 kHz
    static final int DEFAULT_TEMPO_MULTIPLIER = 5;

    static final Map<String, Integer> TEMPO_MULTIPLIER_OVERRIDES = new HashMap<>();
    static {
        // UM3481A: every non-staccato melody aligns exactly with its slot, so
        // each multiplier below is read straight off the recording.
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:1",  Integer.valueOf(5)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:2",  Integer.valueOf(4)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:3",  Integer.valueOf(6)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:4",  Integer.valueOf(3)); //$NON-NLS-1$
        // slot 5 is the staccato melody and could not be aligned
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:6",  Integer.valueOf(5)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:7",  Integer.valueOf(4)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3481A:8",  Integer.valueOf(6)); //$NON-NLS-1$
        // UM3482A: only these two slots align cleanly, see 9.8.
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3482A:9",  Integer.valueOf(5)); //$NON-NLS-1$
        TEMPO_MULTIPLIER_OVERRIDES.put("UM3482A:11", Integer.valueOf(3)); //$NON-NLS-1$
    }

    static int tempoMultiplier(final String chip, final int slot1Based) {
        final var m = TEMPO_MULTIPLIER_OVERRIDES.get(chip + ":" + slot1Based); //$NON-NLS-1$
        return m != null ? m : DEFAULT_TEMPO_MULTIPLIER;
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
        int songCount;   // number of real melodies (before pointer filler repeats)
    }

    /** Main method for testing.
     * @param args Unused.
     * @throws IOException if any problem reading or writting files.
     */
    public static void main(final String[] args) throws IOException {
        System.setOut(new PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));

        final var inputDir = args.length > 0 ? args[0] : "."; //$NON-NLS-1$
        final var outputDir = args.length > 1 ? args[1] : "output"; //$NON-NLS-1$

        final List<Chip> chips = new ArrayList<>();
        final var c1 = loadChip(inputDir, "UM3481A", "um3481araw.bin", "um3481a_offsets.bin", "um3481a_tempos.bin"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (c1 != null) {
			chips.add(c1);
		}
        final var c2 = loadChip(inputDir, "UM3482A", "um3482araw.bin", "um3482_offsets.bin", "um3482_tempos.bin"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
            System.out.printf("%-4s %-7s %-7s %-7s %-7s %-6s %-10s %-8s %-4s%n", //$NON-NLS-1$
                    "#", "start", "end", "words", "tempo", "mult", "duration", "notes", "est"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$

            for (var slot = 0; slot < chip.songCount; slot++) {
                final var start = chip.offsets[slot];
                var end = slot + 1 < chip.offsets.length
                        ? chip.offsets[slot + 1] - 1 : TOTAL_NOTES - 1;
                if (end < start) {
					end = start;
				}
                if (end >= TOTAL_NOTES) {
					end = TOTAL_NOTES - 1;
				}

                final var mult = tempoMultiplier(chip.name, slot + 1);
                final var tempoByte = slot < chip.tempos.length ? chip.tempos[slot] : -1;

                final var audio = synthesizeSong(chip.name, chip.notes, start, end, mult);
                final var melodic = countMelodic(chip.notes, start, end);

                final var filename = String.format("%s_melody_%02d.wav", chip.name.toLowerCase(), slot + 1); //$NON-NLS-1$
                writeWav(Paths.get(outputDir, filename).toString(), audio, SAMPLE_RATE);

                final var measured = TEMPO_MULTIPLIER_OVERRIDES.containsKey(chip.name + ":" + (slot + 1)); //$NON-NLS-1$
                final var estTone = slotUsesEstimatedTone(chip.name, chip.notes, start, end);
                System.out.printf("%-4d %-7d %-7d %-7d %-7d %-6s %7.2f s  %-8d %-4s -> %s%n", //$NON-NLS-1$
                        slot + 1, start, end, end - start + 1, tempoByte,
                        mult + (measured ? "*" : ""), //$NON-NLS-1$ //$NON-NLS-2$
                        audio.length / (double) SAMPLE_RATE, melodic,
                        estTone ? "~" : "", filename); //$NON-NLS-1$ //$NON-NLS-2$
            }
            System.out.println("  * tempo multiplier measured directly; the rest use the fallback of " //$NON-NLS-1$
                    + DEFAULT_TEMPO_MULTIPLIER + " (section 6)"); //$NON-NLS-1$
            if ("UM3482A".equals(chip.name)) { //$NON-NLS-1$
                System.out.println("  ~ slot contains a tone code whose frequency is estimated, " //$NON-NLS-1$
                        + "not measured (see 9.1)"); //$NON-NLS-1$
            }
            System.out.println();
        }

        System.out.println("Done. WAV files written to: " + Paths.get(outputDir).toAbsolutePath()); //$NON-NLS-1$
    }

    // ========================================================================
    // LOADING
    // ========================================================================

    static Chip loadChip(final String dir, final String name, final String romFile,
                         final String offsetsFile, final String temposFile) throws IOException {
        final var rom = Paths.get(dir, romFile);
        if (!Files.exists(rom)) {
			return null;
		}

        var off = Paths.get(dir, offsetsFile);
        if (!Files.exists(off)) {
			off = Paths.get(dir, "offsets.bin"); //$NON-NLS-1$
		}
        var tmp = Paths.get(dir, temposFile);
        if (!Files.exists(tmp)) {
			tmp = Paths.get(dir, "tempos.bin"); //$NON-NLS-1$
		}
        if (!Files.exists(off) || !Files.exists(tmp)) {
            System.err.println("Skipping " + name + ": offsets/tempos file not found alongside " + romFile); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        final var romBytes = Files.readAllBytes(rom);
        if (romBytes.length != EXPECTED_ROM_BYTES) {
            System.err.println("Warning: " + romFile + " is " + romBytes.length //$NON-NLS-1$ //$NON-NLS-2$
                    + " bytes; expected " + EXPECTED_ROM_BYTES + ". Continuing anyway."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        final var c = new Chip();
        c.name = name;
        c.notes = decodeNotes(romBytes);
        c.offsets = parseOffsets(Files.readAllBytes(off));
        c.tempos = parseTempos(Files.readAllBytes(tmp));
        c.songCount = countRealSongs(c.offsets);
        return c;
    }

    /**
     * Offset packing is detected by file size (section 2): 24 bytes means 16
     * entries of 12 packed bits, 32 bytes means 16 big-endian 16-bit words.
     */
    static int[] parseOffsets(final byte[] data) {
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
    static int countRealSongs(final int[] offsets) {
        var n = 1;
        while (n < offsets.length && offsets[n] > offsets[n - 1] && offsets[n] < TOTAL_NOTES) {
			n++;
		}
        // The first filler pointer is still strictly greater than the last real
        // one, so it gets counted above; drop it when the value repeats
        // immediately afterwards (the UM3481A dump ends 421, 499, 499, ...).
        if (n < offsets.length && offsets[n] == offsets[n - 1]) {
			n--;
		}
        return n;
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
            final var ms = noteDurationMs(notes[i][0], multiplier);
            final var toneRaw = notes[i][1];

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
