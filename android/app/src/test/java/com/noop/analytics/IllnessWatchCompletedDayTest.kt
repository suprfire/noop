package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Illness early-warning: completed-day history + main-night-scoped signals.
 *
 * Field report: a seated pre-bedtime movie block was detected as sleep, banked into TODAY's
 * row, and the alert fired at 22:30 over a film. Two independent hardenings pin the fix:
 *  - completedHistory() removes the live local/logical day rows before evaluate(), so the
 *    newest accusing night is always a finished one (the #304 carve-out means either key can
 *    hold the just-banked night — both must go);
 *  - evaluate() reads HRV/respiration through the main-night-scoped columns when present, so
 *    a phantom session inside the day's pooled set cannot dilute the accusing day OR its
 *    baseline. The scoped fallback keeps imported/pre-v42 rows on exactly the old path.
 */
class IllnessWatchCompletedDayTest {

    private fun day(
        d: String,
        restingHr: Int? = null,
        avgHrv: Double? = null,
        skinTempDevC: Double? = null,
        respRateBpm: Double? = null,
        mainNightAvgHrv: Double? = null,
        mainNightRespRateBpm: Double? = null,
    ): DailyMetric = DailyMetric(
        deviceId = "test",
        day = d,
        restingHr = restingHr,
        avgHrv = avgHrv,
        skinTempDevC = skinTempDevC,
        respRateBpm = respRateBpm,
        mainNightAvgHrv = mainNightAvgHrv,
        mainNightRespRateBpm = mainNightRespRateBpm,
    )

    private fun iso(offsetDays: Long): String =
        java.time.LocalDate.of(2026, 2, 1).plusDays(offsetDays).toString()

    // ── completedHistory ───────────────────────────────────────────────────────

    @Test
    fun completedHistoryDropsBothLiveKeysAndKeepsEverythingElse() {
        val rows = (0L until 20L).map { day(iso(it), restingHr = 50) }
        val localKey = iso(19)   // the newest row == today's calendar key
        val logicalKey = iso(18) // #304: a pre-midnight sleeper's just-banked night carries the other key

        val kept = IllnessWatch.completedHistory(rows, setOf(localKey, logicalKey))

        assertEquals(18, kept.size)
        assertTrue("no live key may survive", kept.none { it.day == localKey || it.day == logicalKey })
        assertEquals("the newest accusing row is the last COMPLETED day", iso(17), kept.last().day)
    }

    @Test
    fun completedHistoryOnSparseKeysDropsNothing() {
        // Importing history cannot make the filter eat real completed days: only exact
        // local/logical key matches are dropped.
        val rows = listOf(day("2026-02-01", restingHr = 50), day("2026-02-05", restingHr = 50))
        assertEquals(rows, IllnessWatch.completedHistory(rows, setOf("2026-02-03", "2026-02-04")))
    }

    @Test
    fun livePartialDayCannotSupplyTheAccusingNight() {
        // The field case: calm completed nights + a strained yesterday + an IN-PROGRESS today
        // whose just-banked movie block tanked pooled HRV while its resting HR sat at the
        // evening's low. Evaluating the raw list (the old call path) raises — RHR and HRV both
        // pass on the recent pair including today. Evaluating completedHistory(...) drops
        // today: the recent pair is yesterday + a clean completed night, the 2-day means sit
        // inside every threshold, and no flag survives → no banner. That contrast is the
        // regression this pins.
        val baseline = (0L until 29L).map { i ->
            day(iso(i), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0)
        }
        val strainedYesterday = day(iso(29), restingHr = 58, avgHrv = 45.0, skinTempDevC = 0.8, respRateBpm = 14.0)
        val movieToday = day(iso(30), restingHr = 58, avgHrv = 20.0, skinTempDevC = 0.0, respRateBpm = 14.0)
        val all = baseline + listOf(strainedYesterday, movieToday)

        assertNotNull("the old path accused a night still in progress", IllnessWatch.evaluate(all))
        assertNull(
            "with the live day dropped the recent pair no longer clears any threshold twice",
            IllnessWatch.evaluate(IllnessWatch.completedHistory(all, setOf(iso(30), iso(30)))),
        )
    }

    // ── main-night-scoped signals ──────────────────────────────────────────────

    @Test
    fun scopedHrvOutranksDilutedPooledValue() {
        // The recent pair's POOLED HRV reads −67% (a phantom daytime session inside it), while
        // their MAIN-NIGHT values are clean. With scope present only the skin-temp flag may
        // fire (one flag → no banner). Without scope values the identical rows DO raise on HRV
        // + skin temp — that contrast is exactly what the scoping withholds.
        fun rows(scoped: Boolean) =
            (0L until 31L).map { i ->
                day(
                    iso(i), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0,
                    mainNightAvgHrv = if (scoped) 60.0 else null,
                )
            } + listOf(
                day(iso(31), restingHr = 50, avgHrv = 20.0, skinTempDevC = 0.8, respRateBpm = 14.0,
                    mainNightAvgHrv = if (scoped) 60.0 else null),
                day(iso(32), restingHr = 50, avgHrv = 20.0, skinTempDevC = 0.8, respRateBpm = 14.0,
                    mainNightAvgHrv = if (scoped) 60.0 else null),
            )

        assertNull("main-night HRV is clean; pooled dilution must not accuse", IllnessWatch.evaluate(rows(true)))
        val msg = IllnessWatch.evaluate(rows(false))
        assertNotNull(msg)
        assertTrue("unscoped, the same rows DO flag the diluted HRV", msg!!.contains("HRV"))
    }

    @Test
    fun scopedRespOutranksDilutedPooledValue() {
        // Same shape for respiration: a plausible ~15 baseline, recent pooled RSA values up,
        // main-night values clean. RHR is deliberately elevated on the recent pair so the
        // unscoped case reaches two flags — with scope, only the RHR flag survives and a single
        // flag is never a banner.
        val base = (0L until 31L).map { i ->
            day(
                iso(i), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0,
                respRateBpm = if (i % 2 == 0L) 14.0 else 16.0, mainNightRespRateBpm = 15.0,
            )
        }
        val recent = listOf(
            day(iso(31), restingHr = 58, avgHrv = 60.0, skinTempDevC = 0.0,
                respRateBpm = 19.0, mainNightRespRateBpm = 15.0),
            day(iso(32), restingHr = 58, avgHrv = 60.0, skinTempDevC = 0.0,
                respRateBpm = 19.5, mainNightRespRateBpm = 15.0),
        )
        assertNull(
            "scoped main-night resp (clean) outranks the diluted pooled rise",
            IllnessWatch.evaluate(base + recent),
        )
        val unscoped = (base + recent).map { it.copy(mainNightRespRateBpm = null) }
        val msg = IllnessWatch.evaluate(unscoped)
        assertNotNull(msg)
        assertTrue("unscoped, the same rows DO flag respiration", msg!!.contains("respiration"))
    }

    @Test
    fun preV42RowsKeepThePooledPathUnchanged() {
        // Rows carrying ONLY pooled values (pre-v42 / imported): behavior is exactly the old
        // two-flag banner — the scoped-first fallback changes nothing for them.
        val rows = (0L until 31L).map { i ->
            day(iso(i), restingHr = 50, avgHrv = 60.0, skinTempDevC = 0.0, respRateBpm = 14.0)
        } + listOf(
            day(iso(31), restingHr = 58, avgHrv = 45.0, skinTempDevC = 0.8, respRateBpm = 14.0),
            day(iso(32), restingHr = 58, avgHrv = 45.0, skinTempDevC = 0.8, respRateBpm = 14.0),
        )
        val msg = IllnessWatch.evaluate(rows)
        assertNotNull(msg)
        assertTrue("pooled fallback still flags HRV on pre-v42 rows", msg!!.contains("HRV"))
    }
}
