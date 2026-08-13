package com.rpgos.app

import java.lang.reflect.Modifier

internal object PlayerResolutionComponentStateValidator {
    fun validate(component: PlayerResolutionComponent<out PlayerCommandPayload>) {
        var type: Class<*>? = component.javaClass
        while (type != null && type != PlayerResolutionComponent::class.java) {
            type.declaredFields.forEach { field ->
                if (!Modifier.isFinal(field.modifiers)) {
                    throw PlayerDomainEngineStructuralException("MUTABLE_RESOLUTION_COMPONENT_STATE")
                }
                if (!safeFieldType(field.type)) {
                    throw PlayerDomainEngineStructuralException("UNSAFE_RESOLUTION_COMPONENT_STATE")
                }
            }
            type = type.superclass
        }
    }

    private fun safeFieldType(type: Class<*>): Boolean =
        type.isPrimitive ||
            type == java.lang.Long::class.java ||
            type == java.lang.Integer::class.java ||
            type == java.lang.Boolean::class.java ||
            type == java.lang.Short::class.java ||
            type == java.lang.Byte::class.java ||
            type == java.lang.Character::class.java ||
            type == String::class.java ||
            type.isEnum
}
