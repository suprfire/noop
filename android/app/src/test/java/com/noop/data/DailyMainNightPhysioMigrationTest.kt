package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v41 -> v42: main-night-scoped HRV / respiration for the illness early-warning.
 *
 * The day's pooled `avgHrv`/`respRateBpm` intentionally cover every matched session (#525 note).
 * IllnessWatch compares a day against its own baseline, so a phantom daytime session (a seated
 * pre-bedtime movie block read as sleep) dilutes the accusing day AND every baseline night it
 * lands in. These two columns carry the same statistics restricted to the main-night group;
 * the watch prefers them and falls back to the pooled column, which is why the migration is
 * nullable with no default and no backfill — a pre-v42 row must read as "not scoped", not 0.
 *
 * This environment has no Robolectric / Room-testing for migrations (see [AppleStepHourMigrationTest]),
 * so the SQL is exposed as an internal constant and pinned to shape here rather than executed.
 */
class DailyMainNightPhysioMigrationTest {

    @Test
    fun migrationIsTwoNullableAdditiveColumns() {
        val sql = WhoopDatabase.DAILY_MAIN_NIGHT_PHYSIO_MIGRATION_SQL
        assertEquals(
            listOf(
                "ALTER TABLE `dailyMetric` ADD COLUMN `mainNightAvgHrv` REAL",
                "ALTER TABLE `dailyMetric` ADD COLUMN `mainNightRespRateBpm` REAL",
            ),
            sql,
        )
        for (stmt in sql) {
            val upper = stmt.uppercase()
            assertTrue(upper.startsWith("ALTER TABLE"))
            // Nullable and defaultless on purpose: only a re-score knows the scoping, and a
            // DEFAULT 0 would state "this night had zero HRV" about every pre-v42 night.
            assertTrue(!upper.contains("NOT NULL") && !upper.contains("DEFAULT"))
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "RENAME ")) {
                assertTrue("migration must not contain $banned", !upper.contains(banned))
            }
        }
    }

    @Test
    fun migrationSpansTheRightVersions() {
        assertEquals(41, WhoopDatabase.MIGRATION_41_42.startVersion)
        assertEquals(42, WhoopDatabase.MIGRATION_41_42.endVersion)
    }

    @Test
    fun oldRowsAreUnscopedNotNullZero() {
        val old = DailyMetric(deviceId = "my-whoop", day = "2026-09-03", avgHrv = 55.0)
        assertNull("a pre-v42 row carries no scoping; the watch falls back to pooled", old.mainNightAvgHrv)
        assertNull(old.mainNightRespRateBpm)
        // Scoped values round-trip distinctly from the pooled columns.
        assertEquals(58.0, old.copy(mainNightAvgHrv = 58.0).mainNightAvgHrv!!, 0.001)
        assertEquals(14.2, old.copy(mainNightRespRateBpm = 14.2).mainNightRespRateBpm!!, 0.001)
    }
}
