package com.rpgos.app

import java.math.BigInteger

/** BigInteger.longValueExact is unavailable on older supported Android runtimes. Keep its
 * checked conversion contract without relying on the device's Java library version. */
internal fun BigInteger.toExactLongCompat(): Long {
    if (this < EXACT_LONG_MIN || this > EXACT_LONG_MAX)
        throw ArithmeticException("BigInteger out of long range")
    return toLong()
}

private val EXACT_LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
private val EXACT_LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
