package it.anello.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {
    private fun plan(
        recovery: Int? = 80, hrv: Deep.HrvKind? = Deep.HrvKind.STABLE, out: Int = 0,
        acute: Double? = null, chronic: Double? = null, asleep: Int? = 470,
    ) = Coach.advise(recovery, hrv, out, acute, chronic, asleep, 480)

    @Test fun goodDayAllowsIntensity() {
        val a = plan()!!
        assertEquals(Coach.Plan.INTENSITY, a.plan)
        assertEquals(listOf("Recupero 80: buono"), a.reasons)
    }

    @Test fun recoveryBands() {
        assertEquals(Coach.Plan.ENDURANCE, plan(recovery = 60)!!.plan)
        assertEquals(Coach.Plan.EASY, plan(recovery = 45)!!.plan)
        assertEquals(Coach.Plan.REST, plan(recovery = 20)!!.plan)
    }

    @Test fun otherSignalsLowerTheAdviceAndSayWhy() {
        // Recupero buono ma settimana di carico non smaltito.
        val strain = plan(hrv = Deep.HrvKind.STRAIN)!!
        assertEquals(Coach.Plan.EASY, strain.plan)
        assertTrue(strain.reasons.any { it.contains("carico non smaltito") })
        // Due segni vitali fuori norma: riposo, qualunque sia il Recupero.
        assertEquals(Coach.Plan.REST, plan(out = 2)!!.plan)
        assertEquals(Coach.Plan.ENDURANCE, plan(out = 1)!!.plan)
        // Carico recente del 50% sopra l'abituale.
        val load = plan(acute = 90.0, chronic = 60.0)!!
        assertEquals(Coach.Plan.ENDURANCE, load.plan)
        assertTrue(load.reasons.any { it.contains("50%") })
        // Con poco storico di carico il confronto non vale.
        assertEquals(Coach.Plan.INTENSITY, plan(acute = 30.0, chronic = 10.0)!!.plan)
        // Notte molto corta.
        assertEquals(Coach.Plan.ENDURANCE, plan(asleep = 300)!!.plan)
    }

    @Test fun theLowestSignalWinsAndWeakerOnesAreNotListed() {
        val a = plan(recovery = 20, hrv = Deep.HrvKind.LOW)!!
        assertEquals(Coach.Plan.REST, a.plan)
        assertEquals(1, a.reasons.size)
    }

    @Test fun noDataNoAdvice() {
        assertNull(plan(recovery = null, hrv = null))
    }
}
