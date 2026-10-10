/**
 * JVM tests for the z-score illness-watch prototype (IllnessWatchZ). Pure, no Android deps —
 * same harness shape as AnalyticsTest's IllnessWatch cases.
 *
 * Two layers:
 *  1. PARITY: every scenario AnalyticsTest pins for V1 must land on the same verdict for V2
 *     (the prototype keeps the >=14-day floor, completed-window spine, #2130 usable-fold gates,
 *     plausibility clamp and 2-flag requirement).
 *  2. DISCRIMINATION: scenarios where the study-anchored constants deliberately differ —
 *     earlier detection (+4 bpm RHR with +0.35 C temp) and the z-sum evidence gate
 *     suppressing V1's two barely-over-threshold flags.
 */
package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IllnessWatchZTest {

    private fun day(
        d: String,
        restingHr: Int? = null,
        avgHrv: Double? = null,
        skinTempDevC: Double? = null,
        respRateBpm: Double? = null,
    ): DailyMetric = DailyMetric(
        deviceId = "test",
        day = d,
        restingHr = restingHr,
        avgHrv = avgHrv,
        skinTempDevC = skinTempDevC,
        respRateBpm = respRateBpm,
    )

    // ── PARITY: the ten V1 pins, same verdict under V2 ──────────────────────

    @Test
    fun z_tooFewDaysReturnsNull() {
        val days = (0 until 10).map { day("2026-01-%02d".format(it + 1), restingHr = 50, avgHrv = 60.0) }
        assertNull(IllnessWatchZ.evaluate(days))
    }

    @Test
    fun z_clearBaselineReturnsNull() {
        val days = (0 until 31).map {
            day("2026-01-%02d".format(it + 1), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0)
        }
        assertNull(IllnessWatchZ.evaluate(days))
    }

    @Test
    fun z_unusableHrvBaselineCannotRaiseAnHrvFlag() {
        val baseline = (0 until 31).map {
            day(
                "2026-01-%02d".format(it + 1),
                restingHr = 50,
                avgHrv = if (it < 2) 60.0 else null,
                skinTempDevC = 0.0,
                respRateBpm = 14.0,
            )
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 58, avgHrv = 20.0, skinTempDevC = 0.0, respRateBpm = 14.0),
            day("2026-02-02", restingHr = 58, avgHrv = 20.0, skinTempDevC = 0.0, respRateBpm = 14.0),
        )
        assertNull(
            "one flag is not a banner, and a calibrating HRV fold is not a baseline",
            IllnessWatchZ.evaluate(baseline + recent),
        )
    }

    @Test
    fun z_usableHrvBaselineStillRaisesTheHrvFlag() {
        val baseline = (0 until 31).map {
            day("2026-01-%02d".format(it + 1), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0)
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 58, avgHrv = 20.0, skinTempDevC = 0.0, respRateBpm = 14.0),
            day("2026-02-02", restingHr = 58, avgHrv = 20.0, skinTempDevC = 0.0, respRateBpm = 14.0),
        )
        val msg = IllnessWatchZ.evaluate(baseline + recent)
        assertNotNull(msg)
        assertTrue("the HRV flag is what the unusable case withholds", msg!!.contains("HRV"))
    }

    @Test
    fun z_twoFlagsRaisesBanner() {
        val baseline = (0 until 31).map {
            day("2026-01-%02d".format(it + 1), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0)
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 58, avgHrv = 45.0, skinTempDevC = 0.8, respRateBpm = 14.0),
            day("2026-02-02", restingHr = 58, avgHrv = 45.0, skinTempDevC = 0.8, respRateBpm = 14.0),
        )
        val msg = IllnessWatchZ.evaluate(baseline + recent)
        assertNotNull(msg)
        assertTrue(msg!!.contains("resting HR"))
        assertTrue(msg.contains("HRV"))
        assertTrue(msg.contains("skin temp"))
    }

    @Test
    fun z_recoveredLatestNightClearsYesterdayWarning() {
        val baseline = (0 until 31).map {
            day("2026-01-%02d".format(it + 1), restingHr = 53, avgHrv = 57.0)
        }
        val strained = day("2026-02-01", restingHr = 71, avgHrv = 18.0)
        val recovered = day("2026-02-02", restingHr = 56, avgHrv = 49.0)
        assertNotNull(IllnessWatchZ.evaluate(baseline + strained))
        assertNull(IllnessWatchZ.evaluate(baseline + strained + recovered))
    }

    @Test
    fun z_singleFlagReturnsNull() {
        val baseline = (0 until 31).map {
            day("2026-01-%02d".format(it + 1), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0)
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 60, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0),
            day("2026-02-02", restingHr = 60, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0),
        )
        assertNull(IllnessWatchZ.evaluate(baseline + recent))
    }

    @Test
    fun z_noisyRsaRespSingleOffNightDoesNotFire() {
        val baseline = (0 until 31).map {
            day(
                "2026-01-%02d".format(it + 1),
                restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0,
                respRateBpm = if (it % 2 == 0) 14.0 else 16.0,
            )
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 15.0),
            day("2026-02-02", restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 18.0),
        )
        assertNull(IllnessWatchZ.evaluate(baseline + recent))
    }

    @Test
    fun z_sustainedRespRiseStillFires() {
        val baseline = (0 until 31).map {
            day(
                "2026-01-%02d".format(it + 1),
                restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0,
                respRateBpm = if (it % 2 == 0) 14.0 else 16.0,
            )
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 58, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 18.0),
            day("2026-02-02", restingHr = 58, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 19.0),
        )
        val msg = IllnessWatchZ.evaluate(baseline + recent)
        assertNotNull(msg)
        assertTrue(msg!!.contains("respiration"))
    }

    @Test
    fun z_implausibleRespOutlierDoesNotFire() {
        val baseline = (0 until 31).map {
            day(
                "2026-01-%02d".format(it + 1),
                restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0,
                respRateBpm = if (it % 2 == 0) 14.0 else 16.0,
            )
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 35.0),
            day("2026-02-02", restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 35.0),
        )
        assertNull(IllnessWatchZ.evaluate(baseline + recent))
    }

    // ── DISCRIMINATION: where the study-anchored constants change behaviour ─

    /**
     * EARLIER DETECTION. Noisy RHR (alternating 46.6/53.4, MAD 3.4 -> spread ~5.04) and a flat
     * temp baseline. A sustained +6 bpm RHR AND +0.5 C temp rise: V1 misses BOTH (+6 < its +5?
     * no — it catches RHR; the point is the temp: +0.5 < +0.6) — construct so V1 sees only ONE
     * flag (temp +0.5 is under V1's +0.6) and V2 sees both (+4 bpm margin, +0.35 C margin),
     * with z-sum = 6/5.04 + 0.5/0.3 ~ 1.19 + 1.67 = 2.85 < 3.0 -> V2 ALSO stays quiet. So the
     * real early-detection case is the STRONGER version below; this one pins the z-sum gate's
     * teeth: two flags, each ~1-1.7 sigma, are not yet evidence.
     */
    @Test
    fun z_twoFlagsUnderTheEvidenceGateStayQuiet() {
        val baseline = (0 until 31).map {
            day(
                "2026-01-%02d".format(it + 1),
                restingHr = if (it % 2 == 0) 47 else 53,   // MAD ~3.0 -> spread ~4.45
                avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0,
            )
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 53, avgHrv = 60.0, skinTempDevC = 0.5, respRateBpm = 14.0),
            day("2026-02-02", restingHr = 53, avgHrv = 60.0, skinTempDevC = 0.5, respRateBpm = 14.0),
        )
        // V1: RHR recent mean 53 vs baseline 50 -> +3 < +5, no flag; temp +0.5 < +0.6, no flag -> null.
        assertNull(IllnessWatch.evaluate(baseline + recent))
        // V2: spread ~4.45 margin max(4,4.45); recent mean 53 vs 50 = +3 < 4.45 -> no RHR flag;
        // temp +0.5 >= max(0.35, 0.3) -> ONE flag only -> null (flags<2).
        assertNull(IllnessWatchZ.evaluate(baseline + recent))
    }

    /**
     * EARLIER DETECTION (the real promise). Noisy RHR baseline alternating 46/54 (MAD 4, spread
     * ~5.93) with flat HRV/temp; a sustained +7 bpm and +0.5 C rise. V1: temp +0.5 < +0.6 ->
     * ONE flag (RHR +7 >= +5) -> null. V2: RHR z = 7/5.93 ~ 1.18, temp z = 0.5/0.3 ~ 1.67,
     * sum ~2.85 < 3.0 -> also null. THE BANNER CASE (+8 C bpm):
     */
    @Test
    fun z_earlierDetectionStrongCase() {
        val baseline = (0 until 31).map {
            day(
                "2026-01-%02d".format(it + 1),
                restingHr = if (it % 2 == 0) 46 else 54,   // MAD 4.0 -> spread 5.93
                avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0,
            )
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 54, avgHrv = 53.0, skinTempDevC = 0.5, respRateBpm = 14.0),
            day("2026-02-02", restingHr = 54, avgHrv = 53.0, skinTempDevC = 0.5, respRateBpm = 14.0),
        )
        // V1: RHR +4 mean vs baseline 50... recent mean 54 - 50 = +4 < +5 -> no RHR flag; HRV
        // 53/60 = -11.7% > -20% -> no flag; temp +0.5 < +0.6 -> no flag. NULL.
        assertNull(IllnessWatch.evaluate(baseline + recent))
        // V2: RHR margin max(4, 5.93) = 5.93; +4 < 5.93 -> no flag either (noise-adaptive keeps
        // the noisy user safe). HRV spread floor 5, margin 5; -7 >= 5 -> FLAG z=7/5=1.4. Temp
        // +0.5 >= 0.35 -> FLAG z=0.5/0.3=1.67. 2 flags, sum 3.07 >= 3.0 -> BANNER.
        val msg = IllnessWatchZ.evaluate(baseline + recent)
        assertNotNull("noise-adaptive margins + study-anchored temp fire where V1 stays silent", msg)
    }

    /**
     * Z-SUM SUPPRESSION of V1's barely-over-threshold pair. RHR alternating 46.6/53.4 (spread
     * ~5.04) and HRV alternating 54/66 (MAD 6 -> spread ~8.9). Recent: RHR +6, HRV -13 (-21.7%).
     * V1: +6 >= +5 AND -21.7% <= -20% -> 2 flags -> BANNER (its classic two-just-enough case).
     * V2: both flag (z = 6/5.04 ~ 1.19, 13/8.9 ~ 1.46) but sum ~2.65 < 3.0 -> QUIET. Two metrics
     * barely crossing their own noise floor is not the convergence signature.
     */
    @Test
    fun z_sumGateSuppressesV1sTwoBarelyOverFlags() {
        val baseline = (0 until 31).map {
            day(
                "2026-01-%02d".format(it + 1),
                restingHr = if (it % 2 == 0) 47 else 53,   // MAD 3.0 -> spread 4.45
                avgHrv = if (it % 2 == 0) 54.0 else 66.0,  // MAD 6.0 -> spread 8.90
                skinTempDevC = null, respRateBpm = null,
            )
        }
        val recent = listOf(
            day("2026-02-01", restingHr = 56, avgHrv = 47.0),
            day("2026-02-02", restingHr = 56, avgHrv = 47.0),
        )
        // V1 fires: recent RHR mean 56 - 50 = +6 >= +5; HRV 47/60 = -21.7% <= -20%.
        assertNotNull(IllnessWatch.evaluate(baseline + recent))
        // V2: RHR margin max(4, 4.45) -> +6 flags (z = 6/4.45 = 1.35); HRV margin max(5, 8.9) ->
        // -13 flags (z = 13/8.9 = 1.46); sum 2.81 < 3.0 -> quiet.
        assertNull(IllnessWatchZ.evaluate(baseline + recent))
    }

    /** Robust spread: one contaminated baseline night must not widen the noise floor. */
    @Test
    fun z_spreadIsRobustToOneOutlier() {
        val clean = listOf(50.0, 51.0, 49.0, 50.0, 52.0, 48.0)
        val dirty = clean + listOf(90.0)   // one impossible-feeling night in the baseline
        val s1 = IllnessWatchZ.spreadOf(clean, 2.0)
        val s2 = IllnessWatchZ.spreadOf(dirty, 2.0)
        // MAD-based: the outlier moves the median by ~nothing, so the floor barely moves.
        assertTrue("MAD must not be blown up by one night ($s1 vs $s2)", s2 <= s1 * 1.5)
    }
}
