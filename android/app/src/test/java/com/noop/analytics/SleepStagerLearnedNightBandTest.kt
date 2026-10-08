package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Learned-timing placement of the daytime false-sleep guard (illness early-warning fix).
 *
 * The fixed [11,20) clock band classifies any window centered at 20:00+ as "overnight", so a
 * seated pre-bedtime stretch — movie-watching stillness with a relaxing but NOT dipping HR —
 * is waved through for anyone habitually asleep before ~22:00, then stitches onto the real
 * night and poisons the day's physiology. With the #547 learner's (bedtime, wake) threaded in,
 * the band becomes the complement of the wearer's habitual night ± learnedNightGraceMin, and
 * that same block faces the ordinary daytime bar (#90): long enough AND a real cardiac dip.
 *
 * Cold-start (learnedNightSec = null) is covered by every existing case in
 * [SleepStagerDaytimeGuardTest], which stays byte-identical by construction.
 */
class SleepStagerLearnedNightBandTest {

    private val dev = "test"

    /** 2025-06-10 00:00:00 UTC — the same fixed midnight [SleepStagerDaytimeGuardTest] uses. */
    private val refMidnight = 1_749_513_600L

    private fun startAtHour(hourUTC: Int): Long = refMidnight + hourUTC * 3_600L

    private fun stillGravity(start: Long, durationS: Int): List<GravitySample> =
        (0 until durationS).map { GravitySample(deviceId = dev, ts = start + it, x = 0.0, y = 0.0, z = 1.0) }

    private fun activeGravity(start: Long, durationS: Int): List<GravitySample> =
        (0 until durationS).map { i ->
            val phase = (i % 2) * 0.5
            GravitySample(deviceId = dev, ts = start + i, x = phase, y = 0.0, z = 1.0)
        }

    private fun hrStream(start: Long, durationS: Int, bpm: Int): List<HrSample> =
        (0 until durationS).map { HrSample(deviceId = dev, ts = start + it, bpm = bpm) }

    /** Learned night 23:30 → 07:30 as local seconds-of-day; grace ±1 h puts the daytime band
     *  at [08:30, 22:30). The movie block below is centered at 22:00 — INSIDE that band. */
    private val learnedNight = (23L * 3600 + 30 * 60) to (7L * 3600 + 30 * 60)

    /** 11 h of active evening (HR 80) establishes the day baseline; the 2 h still block at
     *  21:00-23:00 runs at HR 78 — relaxed but nowhere near the ×0.95 dip a real sleep shows
     *  (76). This is the seated-movie capture verbatim. */
    private fun movieEvening(): Pair<List<HrSample>, List<GravitySample>> {
        val dayStart = startAtHour(10)
        val dayDur = 11 * 60 * 60 // 10:00 → 21:00 active
        val blockStart = dayStart + dayDur
        val blockDur = 2 * 60 * 60
        val hr = hrStream(dayStart, dayDur, 80) + hrStream(blockStart, blockDur, 78)
        val grav = activeGravity(dayStart, dayDur) + stillGravity(blockStart, blockDur)
        return hr to grav
    }

    @Test
    fun preBedtimeStillBlockReadsAsOvernightUnderTheFixedBand() {
        // The bug, pinned: center 22:00 is past hour 20, so the fixed band never applies the
        // daytime guard and the block registers as sleep with no cardiac dip required.
        val (hr, grav) = movieEvening()
        val sessions = SleepStager.detectSleep(hr = hr, gravity = grav)
        assertEquals("fixed band: pre-bedtime stillness slips through as sleep", 1, sessions.size)
    }

    @Test
    fun learnedNightBandHoldsTheSameBlockToTheDaytimeBar() {
        // Same streams, learned (23:30 → 07:30) night supplied: the block center falls inside
        // the learned daytime band [08:30, 22:30), the guard runs, and HR 78 shows no dip
        // below 80 × 0.95 → rejected. The pre-bedtime false night is gone at the detector root.
        val (hr, grav) = movieEvening()
        val sessions = SleepStager.detectSleep(hr = hr, gravity = grav, learnedNightSec = learnedNight)
        assertTrue("learned band: a no-dip pre-bedtime block must be rejected", sessions.isEmpty())
    }

    @Test
    fun learnedBandStillRegistersARealNapWithADip() {
        // The learned band relocates the guard, it does not raise it: a genuine 2 h afternoon
        // nap (HR 60 vs baseline 80, a clear dip past ×0.95) still registers under it.
        val dayStart = startAtHour(10)
        val dayDur = 3 * 60 * 60
        val napStart = dayStart + dayDur // 13:00, inside both bands
        val napDur = 2 * 60 * 60
        val hr = hrStream(dayStart, dayDur, 80) + hrStream(napStart, napDur, 60)
        val grav = activeGravity(dayStart, dayDur) + stillGravity(napStart, napDur)

        val sessions = SleepStager.detectSleep(hr = hr, gravity = grav, learnedNightSec = learnedNight)
        assertEquals("a real dipping nap inside the learned band must still register", 1, sessions.size)
    }

    @Test
    fun learnedBandKeepsTheRealNightUnaffected() {
        // The wearer's actual night (23:30 → 06:30 with a deep HR dip) sits OUTSIDE the learned
        // daytime band and registers unchanged whether or not the band is supplied — the fix
        // must never touch the main sleep, only the block that masquerades as its prelude.
        val dayStart = startAtHour(12)
        val dayDur = 10 * 60 * 60 // 12:00 → 22:00 active (HR 80)
        val sleepStart = dayStart + dayDur + 30 * 60 // 22:30 onset, habitual-ish
        val sleepDur = 7 * 60 * 60
        val hr = hrStream(dayStart, dayDur, 80) + hrStream(sleepStart, sleepDur, 52)
        val grav = activeGravity(dayStart, dayDur) + stillGravity(sleepStart, sleepDur)

        val fixed = SleepStager.detectSleep(hr = hr, gravity = grav)
        val learned = SleepStager.detectSleep(hr = hr, gravity = grav, learnedNightSec = learnedNight)
        assertEquals(1, fixed.size)
        assertEquals("the real night is staged identically with or without the learned band",
            fixed.size, learned.size)
        assertTrue(learned[0].start >= sleepStart)
    }

    @Test
    fun degenerateLearnedNightFallsBackToTheFixedBand() {
        // bed == wake (learner produced nonsense) must fall back to the fixed band, never
        // collapse the day into all-daytime: same accepted session as the unthreaded call.
        val dayStart = startAtHour(0)
        val dayDur = 3 * 60 * 60
        val sleepStart = dayStart + dayDur // 03:00 center, overnight under the fixed band
        val sleepDur = 2 * 60 * 60
        val hr = hrStream(dayStart, dayDur, 80) + hrStream(sleepStart, sleepDur, 52)
        val grav = activeGravity(dayStart, dayDur) + stillGravity(sleepStart, sleepDur)

        val degenerate = SleepStager.detectSleep(
            hr = hr, gravity = grav, learnedNightSec = (23L * 3600) to (23L * 3600),
        )
        assertEquals("invalid learned night = fixed band, byte-identical", 1, degenerate.size)
    }
}
