package com.soledad.app.rag

import com.soledad.app.nucleo.MiniYaml
import com.soledad.app.nucleo.mapa

/** Ajustes de `flujo_bot/integracion_rag/config.yaml`. Todo lo que falte o venga mal toma el valor por defecto. */
data class ConfigRag(
    /** Proveedor y modelo principal (NVIDIA NIM). */
    val proveedor: String = "nvidia",
    val modelo: String = "nvidia/nemotron-3.5-super-vl-120b-a12b",
    /** Respaldo si el principal falla o no tiene cupo. Vacío = sin respaldo. */
    val proveedorRespaldo: String = "gemini",
    val modeloRespaldo: String = "gemini-2.5-flash",
    /** Embeddings de NVIDIA para la búsqueda por significado. Vacío = solo BM25 (índice local). */
    val modeloEmbeddings: String = "nvidia/nemotron-3.5-embed-1b",
    val umbralSemantico: Double = 0.2,
    val topK: Int = 3,
    val puntajeMinimo: Double = 1.0,
    val maxTokens: Int = 200,
    /** Cantidad de turnos previos del mismo chat que viajan en el prompt (0 = ninguno). */
    val historial: Int = 4,
    val temperatura: Double = 0.5,
    val personalidad: String = PERSONALIDAD,
    val etiquetaActiva: Boolean = false,
    val etiqueta: String = "(respuesta automática)",
    val cacheDias: Int = 0,
    val permitirSinBase: Boolean = true,
) {
    companion object {
        const val PERSONALIDAD = "Respondés los mensajes de WhatsApp del dueño de este teléfono mientras no está. " +
            "Escribís en español, en primera persona, como lo haría él, cálido y breve."

        fun desdeYaml(texto: String?): ConfigRag {
            val d = ConfigRag()
            if (texto.isNullOrBlank()) return d
            val m = try { MiniYaml.parse(texto).mapa() } catch (e: Exception) { return d }
            fun s(k: String) = m[k]?.toString()
            return ConfigRag(
                proveedor = s("proveedor")?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: d.proveedor,
                modelo = s("modelo")?.takeIf { it.isNotBlank() } ?: d.modelo,
                proveedorRespaldo = s("proveedor_respaldo")?.trim()?.lowercase() ?: d.proveedorRespaldo,
                modeloRespaldo = s("modelo_respaldo") ?: d.modeloRespaldo,
                modeloEmbeddings = s("modelo_embeddings") ?: d.modeloEmbeddings,
                umbralSemantico = s("umbral_semantico")?.toDoubleOrNull()?.coerceIn(-1.0, 1.0) ?: d.umbralSemantico,
                topK = s("top_k")?.toIntOrNull()?.coerceIn(1, 10) ?: d.topK,
                puntajeMinimo = s("puntaje_minimo")?.toDoubleOrNull() ?: d.puntajeMinimo,
                maxTokens = s("max_tokens")?.toIntOrNull()?.coerceAtLeast(16) ?: d.maxTokens,
                historial = s("historial")?.toIntOrNull()?.coerceIn(0, 20) ?: d.historial,
                temperatura = s("temperatura")?.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: d.temperatura,
                personalidad = s("personalidad")?.takeIf { it.isNotBlank() } ?: d.personalidad,
                etiquetaActiva = s("etiqueta_activa")?.toBooleanStrictOrNull() ?: d.etiquetaActiva,
                etiqueta = s("etiqueta") ?: d.etiqueta,
                cacheDias = s("cache_dias")?.toIntOrNull()?.coerceAtLeast(0) ?: d.cacheDias,
                permitirSinBase = s("permitir_sin_base")?.toBooleanStrictOrNull() ?: d.permitirSinBase,
            )
        }
    }
}
