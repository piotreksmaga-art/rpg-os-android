package com.rpgos.app

/**
 * Narrows Kotlin inference for chained structural-validation error lists.
 * The receiver can only be the statically empty list and the operation is pure.
 */
internal operator fun List<Nothing>.plus(other: List<String>): List<String> = other
