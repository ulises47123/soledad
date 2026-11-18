package com.soledad.app.lista

import com.soledad.app.nucleo.MiniYaml
import com.soledad.app.nucleo.lista
import com.soledad.app.nucleo.mapa

enum class ModoLista { EXCLUIDOS, PERMITIDOS }

data class Contacto(val id: String, val nombre: String)

data class ConfigLista(
    val interruptorGeneral: Boolean = false,
    val modo: ModoLista = ModoLista.PERMITIDOS,
    val grupos: Boolean = false,
    val horarioActivo: Boolean = false,
    val desde: String = "08:00",
    val hasta: String = "22:00",
    val bloqueadas: List<String> = listOf("dinero", "plata", "clave", "contraseña", "codigo", "banco", "tarjeta", "transferencia", "deposito", "pin", "cvv", "cbu", "alias"),
    val limiteRespuestasHora: Int = 10,
    val contactos: List<Contacto> = emptyList(),
) {
    companion object {
        fun desdeYaml(texto: String?): ConfigLista {
            val d = ConfigLista()
            if (texto.isNullOrBlank()) return d
            val r = MiniYaml.parse(texto).mapa()
            val h = r["horario"].mapa()
            return ConfigLista(
                interruptorGeneral = r["interruptor_general"] as? Boolean ?: d.interruptorGeneral,
                modo = if (r["modo"]?.toString()?.trim()?.lowercase() == "excluidos") ModoLista.EXCLUIDOS else ModoLista.PERMITIDOS,
                grupos = r["grupos"] as? Boolean ?: d.grupos,
                horarioActivo = h["activo"] as? Boolean ?: d.horarioActivo,
                desde = h["desde"]?.toString() ?: d.desde,
                hasta = h["hasta"]?.toString() ?: d.hasta,
                bloqueadas = if (r.containsKey("bloqueadas")) r["bloqueadas"].lista().mapNotNull { it?.toString() } else d.bloqueadas,
                limiteRespuestasHora = (r["limite_respuestas_hora"] as? Number)?.toInt() ?: d.limiteRespuestasHora,
                contactos = r["contactos"].lista().mapNotNull {
                    val m = it.mapa()
                    val id = m["id"]?.toString() ?: return@mapNotNull null
                    Contacto(id, m["nombre"]?.toString() ?: id)
                },
            )
        }
    }
}

/** Escritor mínimo: solo produce lo que MiniYaml sabe leer. Las cadenas siempre van entre comillas (un id numérico seguiría siendo texto). */
object EscritorYaml {
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    fun escribir(c: ConfigLista): String = buildString {
        appendLine("interruptor_general: ${c.interruptorGeneral}")
        appendLine("modo: ${if (c.modo == ModoLista.PERMITIDOS) "permitidos" else "excluidos"}")
        appendLine("grupos: ${c.grupos}")
        appendLine("horario:")
        appendLine("  activo: ${c.horarioActivo}")
        appendLine("  desde: ${q(c.desde)}")
        appendLine("  hasta: ${q(c.hasta)}")
        appendLine("limite_respuestas_hora: ${c.limiteRespuestasHora}")
        appendLine("bloqueadas: [${c.bloqueadas.joinToString(", ") { q(it) }}]")
        if (c.contactos.isEmpty()) appendLine("contactos: []")
        else {
            appendLine("contactos:")
            c.contactos.forEach { appendLine("  - id: ${q(it.id)}"); appendLine("    nombre: ${q(it.nombre)}") }
        }
    }
}
