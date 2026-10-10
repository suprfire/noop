package com.noop.analytics

import com.noop.data.DailyMetric
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Prototype: z-score / noise-adaptive variant of [IllnessWatch] (NOT wired into any call site).
 *
 * Motivation (literature-anchored): the fixed thresholds in [IllnessWatch.evaluate] sit at the
 * conservative edge of the published effect sizes, and NO published study validates FIXED
 * individual-level cutoffs — every detection algorithm that worked (NightSignal FSM, CuSum,
 * RHRAD) standardizes each deviation by the PERSONAL noise floor. This prototype keeps the
 * structural spine of V1 byte-faithful:
 *   - >= 14 days floor, recent = last 2 COMPLETED wake-days, baseline = ~28 nights ending 3 ago;
 *   - every flag must be abnormal on BOTH the 2-night mean AND the newest day (#2533);
 *   - RHR/HRV require a `usable` fold baseline (#2130); resp keeps the 8..25 plausibility clamp
 *     and the >= 10 valid baseline nights;
 *   - >= 2 flags required to raise a banner (the COVID-RED specificity lesson).
 *
 * What changes per signal:
 *   RHR    +5 bpm          -> max(+4, 1 sigma)  (NightSignal alerts at +3..4 over the personal
 *                                               median; +4 is above healthy fluctuation, well
 *                                               under the observed +7 median illness rise)
 *   HRV    -20% of mean    -> center - max(5ms, 1 sigma) (self-calibrating: a stable user trips
 *                                               at ~-8%, a noisy user needs ~-30%; keeps the
 *                                               usable-fold gate so a calibrating baseline is
 *                                               never accused)
 *   temp   +0.6 C dev      -> center + max(+0.35, 1 sigma) (COVI-GAPP per-phase effects:
 *                                               +0.13/+0.18/+0.30; +0.6 is fever, not early)
 *   resp   +2.5 bpm        -> +1.5 when the baseline is demonstrably CLEAN (>= 28 valid nights
 *                                               AND MAD <= 0.5), else +2.5 (the RSA-variance
 *                                               concern from #1118 stays gated by DATA, not by
 *                                               trust)
 *
 * Noise floor = max(cfg.floorSpread, 1.4826 * MAD(baseline values)) — robust, so one contaminated
 * night cannot widen the band (the repo's own false-positive playbook: contamination is the
 * enemy). sigma enters the MARGIN (noise-adaptive threshold) and the z-sum GATE: banner requires
 * >= 2 flags AND sum of per-flag |z| on the newest day >= 2.0, so two barely-over-threshold
 * metrics cannot stack into a warning.
 *
 * The center is the plain baseline MEAN (V1-compatible), NOT the fold EMA center: the fold seeds
 * at (min+max)/2 of the config range and a 28-night window does not fully converge it, which
 * would bias every accusation toward mid-range — V1 correctly uses the fold only for `usable`.
 */
object IllnessWatchZ {

    /** Same completed-day carve-out as V1 (live local/logical day rows dropped before scoring). */
    fun completedHistory(days: List<DailyMetric>, liveDayKeys: Set<String>): List<DailyMetric> =
        IllnessWatch.completedHistory(days, liveDayKeys)

    // Study-anchored constants (see class doc for provenance).
    private const val RHR_MARGIN_BPM = 4.0        // NightSignal +3..4 over personal median
    private const val TEMP_MARGIN_C = 0.35        // above COVI-GAPP symptomatic +0.30 effect
    private const val RESP_MARGIN_CLEAN = 1.5     // clean-source only (MAD gate below)
    private const val RESP_MARGIN_NOISY = 2.5     // V1 margin, kept for RSA-era history
    private const val RESP_MAD_CLEAN_MAX = 0.5    // bpm MAD at/under which the tight margin applies
    private const val RESP_CLEAN_MIN_NIGHTS = 28  // full baseline window of valid RR to tighten
    private const val Z_SUM_MIN = 3.0             // >= 2 flags AND >= 3 sigma of summed newest-day
                                                  // evidence: each flag is gated at ~1 sigma, so a
                                                  // 2.0 sum would be vacuous; 3.0 means two flags
                                                  // must average 1.5 sigma to raise a banner

    /** Scoped-first readers, byte-identical to V1 (main-night v42 stats, day-pooled fallback). */
    private fun hrvValue(d: DailyMetric): Double? = d.mainNightAvgHrv ?: d.avgHrv
    private fun respValue(d: DailyMetric): Double? = d.mainNightRespRateBpm ?: d.respRateBpm

    /** Robust noise floor: max(config floor, 1.4826 * MAD) — one bad night cannot widen it. */
    internal fun spreadOf(values: List<Double>, floorSpread: Double): Double {
        if (values.isEmpty()) return floorSpread
        val med = median(values)
        val mad = median(values.map { abs(it - med) })
        return maxOf(floorSpread, 1.4826 * mad)
    }

    private fun median(vals: List<Double>): Double {
        val s = vals.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    private fun mean(vals: List<Double>): Double? =
        if (vals.isEmpty()) null else vals.sum() / vals.size.toDouble()

    /**
     * The z denominator: the noise floor, floored at the metric's own [MetricCfg.floorSpread]
     * and never below a small physiological epsilon. A perfectly flat synthetic baseline has
     * MAD 0; without the epsilon its |z| is infinite and any thresholded flag would sail past
     * the z-sum gate on zero evidence. Real nightly series always carry noise above the
     * epsilon, so this only disciplines degenerate inputs.
     */
    private fun zDenominator(spread: Double): Double = maxOf(spread, Z_EPSILON)

    private const val Z_EPSILON = 0.05

    /**
     * Evaluate [days] (oldest -> newest). Returns the same banner shape as V1 when the z-score
     * policy fires; null otherwise. Requires >= 14 rows; see [IllnessWatch.evaluate] for the
     * completed-history contract (the live-day rows must already be dropped).
     */
    fun evaluate(days: List<DailyMetric>): String? {
        if (days.size < 14) return null

        val recent = days.takeLast(2)
        val latest = days.last()
        val base = days.takeLast(31).dropLast(3)

        // #2130: a signal may not accuse off a fold the app would not trust. Same gates as V1.
        val rhrBaseUsable = Baselines.metricCfg["resting_hr"]?.let { cfg ->
            Baselines.foldHistory(base.map { it.restingHr?.toDouble() }, cfg).usable
        } == true
        val hrvBaseUsable = Baselines.metricCfg["hrv"]?.let { cfg ->
            Baselines.foldHistory(base.map { hrvValue(it) }, cfg).usable
        } == true

        val flags = mutableListOf<String>()
        var zSum = 0.0

        fun addFlag(label: String, z: Double) {
            flags.add(label)
            zSum += z // the newest-day |z| is the evidence weight
        }

        run {
            val vals = base.mapNotNull { it.restingHr?.toDouble() }
            val r = mean(recent.mapNotNull { it.restingHr?.toDouble() })
            val b = mean(vals)
            val current = latest.restingHr?.toDouble()
            if (r != null && b != null && current != null && rhrBaseUsable) {
                val spread = spreadOf(vals, Baselines.metricCfg.getValue("resting_hr").floorSpread)
                val margin = maxOf(RHR_MARGIN_BPM, spread)
                if (r >= b + margin && current >= b + margin) {
                    addFlag(
                        "resting HR +${(current - b).roundToInt()} bpm",
                        (current - b) / zDenominator(spread),
                    )
                }
            }
        }

        run {
            val vals = base.mapNotNull { hrvValue(it) }
            val r = mean(recent.mapNotNull { hrvValue(it) })
            val b = mean(vals)
            val current = hrvValue(latest)
            if (r != null && b != null && current != null && b > 0 && hrvBaseUsable) {
                val spread = spreadOf(vals, Baselines.metricCfg.getValue("hrv").floorSpread)
                val margin = maxOf(Baselines.metricCfg.getValue("hrv").floorSpread, spread)
                if (r <= b - margin && current <= b - margin) {
                    addFlag(
                        "HRV -${((1 - current / b) * 100).roundToInt()}%",
                        (b - current) / zDenominator(spread),
                    )
                }
            }
        }

        run {
            val vals = base.mapNotNull { it.skinTempDevC }
            val r = mean(recent.mapNotNull { it.skinTempDevC })
            val current = latest.skinTempDevC
            if (r != null && current != null && !vals.isEmpty()) {
                val b = mean(vals)!!
                val spread = spreadOf(vals, Baselines.metricCfg.getValue("skin_temp").floorSpread)
                val margin = maxOf(TEMP_MARGIN_C, spread)
                if (r >= b + margin && current >= b + margin) {
                    addFlag(
                        "skin temp +${formatOneDp(current - b)}C",
                        (current - b) / zDenominator(spread),
                    )
                }
            }
        }

        run {
            // Keep V1's plausibility clamp: implausible RSA windows never accuse (#1118 twin).
            val plausible = { v: Double -> v in 8.0..25.0 }
            val baseVals = base.mapNotNull { respValue(it) }.filter(plausible)
            val r = mean(recent.mapNotNull { respValue(it) })
            val b = mean(baseVals)
            val current = respValue(latest)
            if (r != null && b != null && current != null && baseVals.size >= 10 &&
                plausible(r) && plausible(b) && plausible(current)
            ) {
                val mad = spreadOf(baseVals, 0.0) / 1.4826 // raw MAD, no floor: the CLEAN probe
                val margin = if (baseVals.size >= RESP_CLEAN_MIN_NIGHTS && mad <= RESP_MAD_CLEAN_MAX)
                    RESP_MARGIN_CLEAN else maxOf(RESP_MARGIN_NOISY, spreadOf(
                        baseVals, Baselines.metricCfg.getValue("resp").floorSpread))
                val spread = spreadOf(baseVals, Baselines.metricCfg.getValue("resp").floorSpread)
                if (r >= b + margin && current >= b + margin) {
                    addFlag("respiration up", (current - b) / zDenominator(spread))
                }
            }
        }

        return if (flags.size >= 2 && zSum >= Z_SUM_MIN) {
            "Your body looks strained - " + flags.joinToString(", ") +
                ". Consider taking it easy."
        } else {
            null
        }
    }

    /** One-decimal formatting identical to V1's locale-independent helper. */
    private fun formatOneDp(value: Double): String {
        val scaled = (value * 10.0).roundToInt()
        val whole = scaled / 10
        val frac = kotlin.math.abs(scaled % 10)
        return "$whole.$frac"
    }
}
