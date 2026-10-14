package com.fantasyidler.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Issue #2035: a single Ancient Sigil times any Topaz stack used to round back to 1, so the
 * stones did nothing on small drops. The fraction is now rolled as the chance of one extra.
 */
class SigilScaledQtyTest {

    @Test
    fun `single drop gains an extra at roughly the stone percentage`() {
        val random = Random(2035)
        val runs = 100_000
        val total = (1..runs).sumOf { sigilScaledQty(1, 1.15, random) }
        val extraRate = (total - runs) / runs.toDouble()
        assertTrue("extra rate $extraRate", extraRate in 0.14..0.16)
    }

    @Test
    fun `result is always the whole part or one more and never below base`() {
        val random = Random(7)
        repeat(1_000) {
            val got = sigilScaledQty(2, 1.25, random)
            assertTrue("got $got", got == 2 || got == 3)
        }
    }

    @Test
    fun `whole multiples need no roll`() {
        assertEquals(4, sigilScaledQty(2, 2.0, Random(1)))
        assertEquals(20, sigilScaledQty(10, 2.0, Random(1)))
    }

    @Test
    fun `large stacks keep the full percentage`() {
        val got = sigilScaledQty(200, 1.10, Random(3))
        assertTrue("got $got", got == 220 || got == 221)
    }
}
