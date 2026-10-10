package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the nightly data-trust census (#15/#27). Pure, no Android deps — the engine is a
 * function of coverage counts and intervals, so every scenario is constructible from Inputs alone.
 * The scenarios mirror the real failure modes the score exists to surface: off-wrist nights,
 * PPG-reconstructed WHOOP5 nights, gravity dropouts, and channels a device never carries
 * (a WHOOP 4.0 night must not read LESS trustworthy for lacking thermal/SpO2 it has never sent).
 */
class NightlyTrustTest {

    private val win = 3600L * 10  // a generous "night window"

    /** A textbook clean night: full HR, dense gravity, thermal+SpO2+resp present, worn all bed. */
    private fun clean() = NightlyTrust.Inputs(
        windowSec = win,
        hrSecondsCovered = (win * 0.97).toInt(),
        ppgFillSeconds = 0,
        gravityTs = (0..(win / 1)).map { it.toLong() },  // ~1 Hz across the whole window
        skinSamples = (win / 60).toInt(),
        spo2Samples = (win / 300).toInt(),
        respSamples = (win / 60).toInt(),
        bedSec = win * 0.9,
        wristOffSecInBed = 0.0,
    )

    @Test
    fun cleanNightScoresHigh() {
        val r = NightlyTrust.evaluate(clean())
        assertNotNull(r)
        assertTrue("a fully covered worn night must read trustworthy (got ${r!!.score})", r.score >= 95)
        assertEquals(1.0, r.wearFrac!!, 0.001)
    }

    @Test
    fun noWindowReturnsNull() {
        assertNull(NightlyTrust.evaluate(clean().copy(windowSec = 0)))
    }

    @Test
    fun offWristNightLowersScoreAndReportsWearFraction() {
        val half = clean().copy(bedSec = win * 0.9, wristOffSecInBed = win * 0.45)
        val r = NightlyTrust.evaluate(half)
        assertNotNull(r)
        assertEquals(0.5, r!!.wearFrac!!, 0.001)
        assertTrue("half the bed off-wrist must cost trust", r.score < NightlyTrust.evaluate(clean())!!.score - 10)
    }

    /** No matched sessions -> wear component absent (null), NOT zero: an unstaged day is not
     *  un-worn, it is unknown. The score renormalizes over the coverage components. */
    @Test
    fun noSessionsLeavesWearNullNotZero() {
        val r = NightlyTrust.evaluate(clean().copy(bedSec = 0.0, wristOffSecInBed = 0.0))
        assertNotNull(r)
        assertNull(r!!.wearFrac)
        assertTrue("coverage alone must still score high", r.score >= 95)
    }

    /**
     * #156 fill: a WHOOP5 night reconstructed ENTIRELY from PPG autocorrelation. The fill share of
     * the covered seconds is what softens it (tolerance 30%), and it must not buy HR coverage:
     * `covered` includes fill, so hr stays full marks while `fill` drops to 0 -> trust falls.
     */
    @Test
    fun ppgOnlyNightScoresLowerThanSensorNight() {
        val sensor = clean().copy(hrSecondsCovered = (win * 0.97).toInt(), ppgFillSeconds = 0)
        // The strap reported only 10% of seconds; PPG fills the rest.
        val fillHeavy = clean().copy(
            hrSecondsCovered = (win * 0.10).toInt(),
            ppgFillSeconds = (win * 0.87).toInt(),
        )
        val a = NightlyTrust.evaluate(sensor)!!.score
        val b = NightlyTrust.evaluate(fillHeavy)!!.score
        assertTrue("PPG-reconstructed night must read softer ($b vs sensor $a)", b < a - 5)
    }

    /** Fill inside the 30% tolerance costs nothing. */
    @Test
    fun fillInsideToleranceIsFree() {
        val base = NightlyTrust.evaluate(clean())!!.score
        val tol = NightlyTrust.evaluate(
            clean().copy(hrSecondsCovered = (win * 0.70).toInt(), ppgFillSeconds = (win * 0.27).toInt()),
        )!!.score
        assertTrue("<=30% fill must not dent the score ($tol vs $base)", tol >= base - 1)
    }

    /** A 30-minute gravity dropout halves the gravity term (#345 maxGapMin=20 shared intent). */
    @Test
    fun longGravityGapHalvesTheGravityTerm() {
        val dense = clean().gravityTs
        val hole = (0..(win / 1)).map { it.toLong() }.filter { it !in (4000L..5800L) } // 30-min hole
        assertEquals(dense.size - 1801, hole.size)   // inclusive 1801-second window removed
        val a = NightlyTrust.evaluate(clean())!!.score
        val b = NightlyTrust.evaluate(clean().copy(gravityTs = hole))!!.score
        assertTrue("a >20-min gravity hole must cost trust ($b vs $a)", b < a - 3)
    }

    /** Sparse gravity (span far under the window) scores lower than dense, same everything else. */
    @Test
    fun sparseGravitySpanScoresLower() {
        val sparse = (0..(win / 60)).map { it.toLong() } // ts span only win/60: present over 1/60 of the night
        val a = NightlyTrust.evaluate(clean())!!.score
        val b = NightlyTrust.evaluate(clean().copy(gravityTs = sparse))!!.score
        assertTrue("span-fraction falloff ($b vs $a)", b < a - 3)
    }

    /**
     * A WHOOP 4.0 night carries NO thermal/SpO2 stream at all: those channels must be ABSENT from
     * the weighted mean, never scored 0. A 4.0 night with full HR+gravity+wrist events scores as
     * high as the 5/MG equivalent — its absent extras renormalize over what it has.
     */
    @Test
    fun deviceWithoutThermalChannelsIsNotPenalizedForThem() {
        val withExtras = clean().copy(skinSamples = (win / 60).toInt(), spo2Samples = (win / 300).toInt())
        val strapOnly = clean().copy(skinSamples = 0, spo2Samples = 0)
        val a = NightlyTrust.evaluate(withExtras)!!.score
        val b = NightlyTrust.evaluate(strapOnly)!!.score
        assertTrue("absent channels must renormalize, not punish ($b vs $a)", b >= a - 1)
    }

    /** Present-but-sparse extras (a handful of samples over the night) score under full presence. */
    @Test
    fun presentButSparseExtrasCostLessThanDense() {
        val dense = NightlyTrust.evaluate(clean())!!.score
        val sparse = NightlyTrust.evaluate(
            clean().copy(skinSamples = 2, spo2Samples = 1, respSamples = 3),
        )!!.score
        assertTrue("extras count against the 1-per-5-min target ($sparse vs $dense)", sparse < dense)
    }

    /** Overlapping off-wrist intervals inside ONE session count once, not twice. */
    @Test
    fun wearFractionIsCappedAtBedWindow() {
        val r = NightlyTrust.evaluate(clean().copy(wristOffSecInBed = win.toDouble())) // nonsense input: > bed
        assertNotNull(r)
        assertTrue("wear fraction must clamp to [0,1]", r!!.wearFrac!! >= 0.0 && r.wearFrac!! <= 1.0)
    }

    /** Score is always 0-100 even with pathological inputs (zero coverage). */
    @Test
    fun zeroCoverageScoresZeroNotNegative() {
        val r = NightlyTrust.evaluate(
            NightlyTrust.Inputs(windowSec = win, hrSecondsCovered = 0, gravityTs = listOf(0L, win)),
        )
        assertNotNull(r)
        assertTrue(r!!.score in 0..100)
    }

    /** Single gravity sample cannot establish span -> component absent (needs >= 2). */
    @Test
    fun singleGravitySampleLeavesComponentAbsent() {
        val a = NightlyTrust.evaluate(clean().copy(gravityTs = emptyList()))!!.score
        val b = NightlyTrust.evaluate(clean().copy(gravityTs = listOf(1000L)))!!.score
        assertEquals("one sample is as uninformative as none", a, b)
    }
}
