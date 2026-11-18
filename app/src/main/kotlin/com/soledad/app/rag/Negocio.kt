package com.soledad.app.rag

import android.content.Context
import java.io.File

/**
 * «Mi negocio»: lo que Soledad sabe del emprendimiento para atender a quien no está agendado.
 * Vive en archivos propios (`filesDir/mi_negocio/<id>.txt`) y NO entra en la base de «Mi información»: así lo personal
 * nunca le llega a un desconocido ni los datos del negocio se mezclan en tus chats personales.
 */
class MiNegocio(private val dir: File) {

    fun leer(s: Seccion): String = File(dir, s.id + ".txt").takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()

    @Synchronized
    fun guardar(s: Seccion, texto: String) {
        dir.mkdirs()
        val f = File(dir, s.id + ".txt")
        val tmp = File(dir, s.id + ".tmp")
        tmp.writeText(texto.trim(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun completas(): Int = SECCIONES.count { leer(it).isNotBlank() }

    /** Sin «Qué vendo» el modo negocio no puede encenderse ni responder. */
    fun listo(): Boolean = leer(SECCIONES[0]).isNotBlank()

    /** Todo lo cargado, para las instrucciones del modelo. "" si falta lo mínimo. */
    fun datos(): String {
        if (!listo()) return ""
        return SECCIONES.mapNotNull { s -> leer(s).trim().takeIf { it.isNotEmpty() }?.let { "${s.titulo.uppercase()}:\n$it" } }
            .joinToString("\n\n").take(MAX_DATOS)
    }

    companion object {
        const val MAX_DATOS = 5_000

        val SECCIONES = listOf(
            Seccion(
                "que_vendo", "Qué vendo", "Qué hacés o vendés y cómo se llama el emprendimiento. Es lo único obligatorio.",
                "Ej.: Hago tortas y mesas dulces por encargo en San Salvador de Jujuy. El emprendimiento se llama …", true,
            ),
            Seccion(
                "precios", "Precios", "Los precios que Soledad puede decir. Lo que no esté acá, no lo inventa.",
                "Ej.: Torta de 20 porciones \$35.000. Docena de alfajores \$9.000. Los precios pueden cambiar.", true,
            ),
            Seccion(
                "atencion", "Horarios, zona, pago y entrega", "Cuándo atendés, dónde entregás y cómo se paga.",
                "Ej.: Atiendo de lunes a sábado de 9 a 19. Retiro por el centro o envío a cargo del cliente. Efectivo o transferencia, seña del 50 %.", true,
            ),
            Seccion(
                "derivar", "Cuándo te paso conmigo", "En qué casos Soledad tiene que decir que les contestás vos.",
                "Ej.: Si quieren confirmar un pedido, piden descuento o preguntan algo que no está acá, decí que les contesto yo en un rato.", true,
            ),
        )

        fun crear(ctx: Context) = MiNegocio(File(ctx.filesDir, "mi_negocio"))
    }
}
