package it.anello.app.score

import it.anello.app.data.DailySummary
import it.anello.app.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnergyTest {
    private val me = UserSettings(weightKg = 70.0, heightCm = 175, birthYear = 1986, sex = "M")
    private val date = LocalDate.of(2026, 11, 28)

    @Test fun restingMetabolism() {
        // Mifflin-St Jeor: 10·70 + 6,25·175 − 5·40 + 5 = 1598,75.
        assertEquals(1598.75, Energy.restingPerDay(me, 2026)!!, 1e-9)
        assertEquals(1598.75 - 166, Energy.restingPerDay(me.copy(sex = "F"), 2026)!!, 1e-9)
        assertNull(Energy.restingPerDay(UserSettings(weightKg = 70.0), 2026))
    }

    @Test fun walking() {
        // 10.000 passi con passo di 0,726 m = 7,26 km; 7,26 · 70 · 0,5 = 254 kcal.
        assertEquals(254.2, Energy.fromSteps(10_000, me)!!, 0.1)
        assertNull(Energy.fromSteps(10_000, UserSettings()))
    }

    @Test fun measuredCaloriesWinOverTheEstimate() {
        val d = Energy.day(DailySummary(date, steps = 10_000, activeKcal = 400.0), me, rideKj = 800.0)
        assertFalse(d.estimated)
        assertEquals(400.0, d.active!!, 1e-9)
        assertEquals(1998.75, d.total!!, 1e-9)
    }

    @Test fun estimateAddsWalkingAndRiding() {
        val d = Energy.day(DailySummary(date, steps = 10_000), me, rideKj = 800.0, dayFraction = 0.5)
        assertTrue(d.estimated)
        assertEquals(1054.2, d.active!!, 0.1)
        assertEquals(799.375, d.resting!!, 1e-9)
        // Senza passi né bici non c'è niente da stimare.
        assertNull(Energy.day(DailySummary(date), me, rideKj = 0.0).active)
        // Senza dati fisici: niente totale.
        assertNull(Energy.day(DailySummary(date, steps = 5000), UserSettings(), rideKj = 0.0).total)
    }
}
