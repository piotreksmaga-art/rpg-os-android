package com.rpgos.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

internal const val DUPLICATE_JSON_OBJECT_KEY = "DUPLICATE_JSON_OBJECT_KEY"

/**
 * Scans the raw serialized command before Json.parseToJsonElement() can collapse
 * duplicate object members. Object member names are decoded as JSON strings, so
 * escaped spellings such as "commandUid" and "\u0063ommandUid" collide.
 */
internal fun rejectDuplicateJsonObjectKeys(serialized: String) {
    StrictJsonDuplicateKeyScanner(serialized).scan()
}

private class StrictJsonDuplicateKeyScanner(private val input: String) {
    private var index: Int = 0

    fun scan() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        if (index != input.length) invalid()
    }

    private fun parseValue() {
        skipWhitespace()
        if (index >= input.length) invalid()
        when (input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> readStringToken()
            else -> parseBareValue()
        }
    }

    private fun parseObject() {
        expect('{')
        skipWhitespace()
        if (consume('}')) return
        val seen = HashSet<String>()
        while (true) {
            skipWhitespace()
            if (index >= input.length || input[index] != '"') invalid()
            val keyToken = readStringToken()
            val decodedKey = try {
                Json.parseToJsonElement(keyToken).jsonPrimitive.content
            } catch (_: Throwable) {
                invalid()
            }
            if (!seen.add(decodedKey)) throw PlayerCommandStructuralException(DUPLICATE_JSON_OBJECT_KEY)
            skipWhitespace()
            expect(':')
            parseValue()
            skipWhitespace()
            if (consume('}')) return
            expect(',')
        }
    }

    private fun parseArray() {
        expect('[')
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (consume(']')) return
            expect(',')
        }
    }

    private fun readStringToken(): String {
        val start = index
        expect('"')
        while (index < input.length) {
            when (val ch = input[index++]) {
                '"' -> return input.substring(start, index)
                '\\' -> {
                    if (index >= input.length) invalid()
                    when (input[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            if (index >= input.length || input[index] !in "0123456789abcdefABCDEF") invalid()
                            index++
                        }
                        else -> invalid()
                    }
                }
                else -> if (ch.code < 0x20) invalid()
            }
        }
        invalid()
    }

    private fun parseBareValue() {
        val start = index
        while (index < input.length) {
            val ch = input[index]
            if (ch.isWhitespace() || ch == ',' || ch == ']' || ch == '}') break
            index++
        }
        if (index == start) invalid()
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }

    private fun consume(expected: Char): Boolean {
        if (index < input.length && input[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) invalid()
    }

    private fun invalid(): Nothing = throw PlayerCommandStructuralException("INVALID_COMMAND_SERIALIZATION")
}
