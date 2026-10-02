package com.rpgos.app

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class ExactIntegerCompatibilityTest {
    @Test fun checkedConversionPreservesBoundsAndRejectsOverflow() {
        listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE).forEach {
            assertEquals(it, BigInteger.valueOf(it).toExactLongCompat())
        }
        listOf(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)).forEach {
            assertTrue(runCatching { it.toExactLongCompat() }.exceptionOrNull() is ArithmeticException)
        }
    }
}
