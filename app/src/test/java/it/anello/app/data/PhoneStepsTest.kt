package it.anello.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class PhoneStepsTest {
    private fun ms(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).toEpochSecond(ZoneOffset.UTC) * 1000

    @Test fun sameDayGetsEverything() {
        assertEquals(mapOf(LocalDate.of(2026, 11, 28) to 500L), PhoneSteps.split(500, ms(28, 9), ms(28, 10), ZoneOffset.UTC))
    }

    @Test fun acrossMidnightIsSplitByTimeAndNothingIsLost() {
        // Dalle 23:00 alle 01:00: metà e metà.
        val out = PhoneSteps.split(101, ms(27, 23), ms(28, 1), ZoneOffset.UTC)
        assertEquals(101L, out.values.sum())
        assertEquals(51L, out[LocalDate.of(2026, 11, 27)])
        assertEquals(50L, out[LocalDate.of(2026, 11, 28)])
    }

    @Test fun nothingToSplit() {
        assertTrue(PhoneSteps.split(0, ms(28, 9), ms(28, 10), ZoneOffset.UTC).isEmpty())
        assertTrue(PhoneSteps.split(10, ms(28, 10), ms(28, 9), ZoneOffset.UTC).isEmpty())
    }
}
