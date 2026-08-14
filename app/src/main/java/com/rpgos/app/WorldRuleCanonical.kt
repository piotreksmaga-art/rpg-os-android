package com.rpgos.app

import java.security.MessageDigest

internal const val WORLD_RULE_CANONICAL_FORMAT = "RPGOS-WORLD-RULE-CANONICAL"
internal const val WORLD_RULE_CANONICAL_VERSION = 1

/**
 * Structural, deterministic pre-hash encoding for Phase-19 identity material.
 * Every node carries an operation/type tag; nullable values use a presence tag;
 * every collection carries a count and every record/section has explicit boundaries.
 */
internal class WorldRuleCanonicalWriter private constructor(
    private val domain: String
) {
    private val material = StringBuilder()

    init {
        token("FORMAT")
        token(WORLD_RULE_CANONICAL_FORMAT)
        token(WORLD_RULE_CANONICAL_VERSION.toString())
        token("DOMAIN")
        token(domain)
    }

    fun field(name: String, value: String) {
        token("FIELD")
        token(name)
        token(value)
    }

    fun longField(name: String, value: Long) = field(name, value.toString())

    fun nullableField(name: String, value: String?) {
        token("NULLABLE_FIELD")
        token(name)
        if (value == null) {
            token("NULL")
        } else {
            token("VALUE")
            token(value)
        }
    }

    fun nullableLongField(name: String, value: Long?) {
        token("NULLABLE_LONG_FIELD")
        token(name)
        if (value == null) {
            token("NULL")
        } else {
            token("VALUE")
            token(value.toString())
        }
    }

    fun section(name: String, block: WorldRuleCanonicalWriter.() -> Unit) {
        token("SECTION_BEGIN")
        token(name)
        block()
        token("SECTION_END")
        token(name)
    }

    fun record(type: String, block: WorldRuleCanonicalWriter.() -> Unit) {
        token("RECORD_BEGIN")
        token(type)
        block()
        token("RECORD_END")
        token(type)
    }

    fun <T> list(name: String, values: List<T>, block: WorldRuleCanonicalWriter.(T) -> Unit) {
        token("LIST_BEGIN")
        token(name)
        token(values.size.toString())
        values.forEachIndexed { index, value ->
            token("ITEM_BEGIN")
            token(index.toString())
            block(value)
            token("ITEM_END")
            token(index.toString())
        }
        token("LIST_END")
        token(name)
    }

    fun domainRef(name: String, ref: DomainRef) {
        record("DOMAIN_REF") {
            field("FIELD_NAME", name)
            field("KIND_UID", ref.kindUid)
            field("UID", ref.uid)
        }
    }

    fun nullableDomainRef(name: String, ref: DomainRef?) {
        record("NULLABLE_DOMAIN_REF") {
            field("FIELD_NAME", name)
            if (ref == null) {
                field("PRESENCE", "NULL")
            } else {
                field("PRESENCE", "VALUE")
                field("KIND_UID", ref.kindUid)
                field("UID", ref.uid)
            }
        }
    }

    fun fingerprint(): String = sha256(material.toString())

    internal fun canonicalMaterial(): String = material.toString()

    private fun token(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        material.append(bytes.size).append(':')
        material.append(value)
        material.append('|')
    }

    companion object {
        fun fingerprint(domain: String, block: WorldRuleCanonicalWriter.() -> Unit): String =
            WorldRuleCanonicalWriter(domain).apply(block).fingerprint()

        internal fun material(domain: String, block: WorldRuleCanonicalWriter.() -> Unit): String =
            WorldRuleCanonicalWriter(domain).apply(block).canonicalMaterial()
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
