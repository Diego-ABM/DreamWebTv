package com.example.dreamweb.managers

/**
 * Preferencia de calidad de la sesión (FR-018): guarda la etiqueta de
 * resolución de la última elección del usuario para proponerla como opción
 * por defecto en el siguiente lanzamiento.
 *
 * Vive SOLO en memoria de proceso — MUST NOT persistirse en disco
 * (clarificación del spec: "Solo dentro de la misma sesión").
 */
object QualityPreferenceMemory {
    @Volatile
    var lastResolutionLabel: String? = null
}
