package com.noop.analytics

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Nightly data-trust scoring — the strap-fit / signal-quality score (#15) fused with the
 * wear-behavior analytics (#27): one 0-100 score per scored night answering "how much of
 * tonight's data can the scores be trusted on", plus the wear fraction beside it.
 *
 * Every number the app shows is downstream of stream coverage, and today the honesty lives
 * scattered: the stager flags SPARSE gravity nights (#345), the HRV card marks over-counted
 * 4.0 nights (#1118), PPG-derived HR fills WHOOP5 seconds the strap never reported (#156),
 * off-wrist intervals come from events, and a charging night means the strap was on the
 * dock, not the wrist. Each consumer guards its own slice; nothing composes them into ONE
 * per-night trust figure a user (and later every alert policy) can read.
 *
 * Design follows the repo idiom: a PURE object (no Android, JVM-testable), inputs assembled
 * by the caller from streams already in scope, persisted by the orchestrator to metricSeries
 * under "night_trust" (0-100) and "night_wear_frac" (0-1). Components with NO data on this
 * device (e.g. skin temp on a WHOOP 4.0, which sends no thermal stream) are ABSENT from the
 * weighted mean, never scored 0 — a 4.0 night is not less trustworthy for lacking a channel
 * it has never carried. Absent components renormalize the weights (the same nil-renorm the
 * Charge breakdown uses).
 *
 * Components and weights:
 *   wear      .30  1 - wristOffInBed/bed — the strap's own on-wrist behavior (#27).
 *                   Absent (no matched sessions) when the night has no bed window.
 *   hr        .30  wall-seconds covered by sensor HR / window; full marks at >= 90%.
 *   fill      .15  share of the covered seconds that are PPG-DERIVED (#156): a WHOOP5 night
 *                   reconstructed by autocorrelation is honest but softer than reported bpm.
 *                   Absent when no PPG fill happened (WHOOP 4.0 nights score full marks
 *                   structurally, so the component is skipped, not padded).
 *   gravity   .15  span-fraction >= 0.5 of the window (the #345 sparse test) with a half-credit
 *                   falloff below it, times a gap penalty: any >20-min hole halves the term
 *                   (the same maxGapMin the stager shreds on).
 *   extras    .10  thermal + SpO2 + respiration presence, each third: channels that EXIST on
 *                   this device; a present-but-sparse channel scores its sample count against
 *                   one sample per 5 minutes of window.
 */
object NightlyTrust {

    /** One scored night's raw coverage census. Timestamps are unix seconds. */
    data class Inputs(
        val windowSec: Long,
        /** Distinct wall-seconds carrying SENSOR (strap-reported) HR. */
        val hrSecondsCovered: Int,
        /** Of the covered seconds, how many exist only as PPG-derived HR (#156 fill). */
        val ppgFillSeconds: Int = 0,
        /** Gravity sample timestamps, oldest->newest (order not required). */
        val gravityTs: List<Long> = emptyList(),
        val skinSamples: Int = 0,
        val spo2Samples: Int = 0,
        val respSamples: Int = 0,
        /** Total matched-sleep bed seconds; 0 (or less) when the night has no sessions. */
        val bedSec: Double = 0.0,
        /** Seconds of the bed window the strap reported OFF-wrist. */
        val wristOffSecInBed: Double = 0.0,
    )

    data class Result(val score: Int, val wearFrac: Double?)

    // Weights sum to 1.0; absent components renormalize over the present ones.
    private const val W_WEAR = 0.30
    private const val W_HR = 0.30
    private const val W_FILL = 0.15
    private const val W_GRAVITY = 0.15
    private const val W_EXTRAS = 0.10

    /** Full marks at >= 90% of wall-seconds covered, linear falloff below. */
    private const val HR_FULL_COVERAGE = 0.90
    /** PPG fill up to 30% of the night scores full marks; 100% fill scores 0. */
    private const val FILL_TOLERANCE = 0.30
    /** The #345 sparse span-fraction, shared intent: <50% gravity span = sparse. */
    private const val GRAVITY_FULL_SPAN = 0.50
    /** Gravity holes past this many seconds halve the gravity term (stager maxGapMin). */
    private const val GRAVITY_MAX_GAP_SEC = 20 * 60.0
    /** Presence target: one thermal/SpO2/resp sample per this many window seconds. */
    private const val EXTRAS_TARGET_SEC = 300.0

    fun evaluate(i: Inputs): Result? {
        if (i.windowSec <= 0) return null
        val win = i.windowSec.toDouble()

        // wear — the strap's own on-wrist behavior; unknown without a matched bed window.
        val wear = if (i.bedSec > 0) (1.0 - i.wristOffSecInBed / i.bedSec).coerceIn(0.0, 1.0) else null

        // hr — fraction of the window carrying sensor HR (fill seconds excluded: they score
        // under `fill`, so a night reconstructed ENTIRELY from PPG cannot buy HR coverage
        // with the very thing fill penalizes).
        val covered = (i.hrSecondsCovered + i.ppgFillSeconds).coerceAtMost(i.windowSec.toInt())
        val hrCov = covered / win
        val hr = min(1.0, hrCov / HR_FULL_COVERAGE)

        // fill — how much of the night is PPG-derived rather than strap-reported.
        val fill: Double? = if (i.ppgFillSeconds > 0)
            (1.0 - ((i.ppgFillSeconds.toDouble() / covered) - FILL_TOLERANCE) / (1.0 - FILL_TOLERANCE))
                .coerceIn(0.0, 1.0)
        else null // no fill happened; the channel is absent, not full marks

        // gravity — span fraction with the #345 falloff, halved by any >20-min hole.
        val gravity: Double? = if (i.gravityTs.size >= 2) {
            val ts = i.gravityTs.sorted()
            val span = (ts.last() - ts.first()).coerceAtLeast(0L).toDouble() / win
            var largestGap = 0L
            for (k in 1 until ts.size) { val g = ts[k] - ts[k - 1]; if (g > largestGap) largestGap = g }
            val spanTerm = min(1.0, span / GRAVITY_FULL_SPAN)
            spanTerm * (if (largestGap > GRAVITY_MAX_GAP_SEC.toLong()) 0.5 else 1.0)
        } else null

        // extras — presence of the optional channels THIS device has; each third.
        val target = win / EXTRAS_TARGET_SEC
        val parts = ArrayList<Double>(3)
        if (i.skinSamples > 0) parts += min(1.0, i.skinSamples / target)
        if (i.spo2Samples > 0) parts += min(1.0, i.spo2Samples / target)
        if (i.respSamples > 0) parts += min(1.0, i.respSamples / target)
        val extras = if (parts.isEmpty()) null else parts.average()

        var num = 0.0; var den = 0.0
        wear?.let { num += W_WEAR * it; den += W_WEAR }
        num += W_HR * hr; den += W_HR            // HR is never absent (the night was scored on it)
        fill?.let { num += W_FILL * it; den += W_FILL }
        gravity?.let { num += W_GRAVITY * it; den += W_GRAVITY }
        extras?.let { num += W_EXTRAS * it; den += W_EXTRAS }
        if (den <= 0.0) return null

        return Result((100.0 * num / den).roundToInt().coerceIn(0, 100), wear)
    }
}
