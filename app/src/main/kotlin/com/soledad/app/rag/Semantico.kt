package com.soledad.app.rag

import android.content.Context
import com.soledad.app.nucleo.ClienteEmbeddings
import java.io.File
import kotlin.math.sqrt

/**
 * Búsqueda por significado con los embeddings de NVIDIA. Los vectores de la base se calculan una vez y quedan en
 * `rag.db`; la pregunta se vectoriza en cada búsqueda. Si no hay clave o no hay red devuelve null y queda solo BM25.
 */
class BuscadorSemantico(
    private val almacen: AlmacenRag,
    private val cliente: ClienteEmbeddings,
    private val clave: () -> String?,
) {
    fun buscar(consulta: String, fragmentos: List<Fragmento>, k: Int, minimo: Double, modelo: String): List<Hallazgo>? {
        if (fragmentos.isEmpty() || consulta.isBlank()) return emptyList()
        val key = clave() ?: return null
        val faltan = fragmentos.filter { almacen.vector(it.id, modelo) == null }
        for (lote in faltan.chunked(16)) {
            val vs = cliente.vectores(key, modelo, lote.map { it.texto }, "passage") ?: return null
            lote.zip(vs).forEach { (f, v) -> almacen.guardarVector(f.id, modelo, v) }
        }
        val q = cliente.vectores(key, modelo, listOf(consulta), "query")?.firstOrNull() ?: return null
        return fragmentos.mapNotNull { f -> almacen.vector(f.id, modelo)?.let { Hallazgo(f, coseno(q, it)) } }
            .filter { it.puntaje >= minimo }
            .sortedByDescending { it.puntaje }
            .take(k)
    }

    companion object {
        fun coseno(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            var p = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { p += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0.0 else p / (sqrt(na) * sqrt(nb))
        }
    }
}

/** Una sección de «Mi información». `alSistema` = va directo a las instrucciones del modelo (estilo y límites), no a la búsqueda. */
class Seccion(val id: String, val titulo: String, val explicacion: String, val ejemplo: String, val alSistema: Boolean)

/**
 * «Mi información»: la base de conocimiento personal, por secciones. El texto de cada sección se guarda tal cual
 * (`filesDir/mi_informacion/<id>.txt`) para editarlo; las secciones de datos se cargan además como fuentes del RAG.
 * Empieza VACÍA: los textos de ejemplo son solo pistas en el campo, no datos.
 */
class MiInformacion(private val dir: File, private val conocimiento: Conocimiento?) {

    fun leer(s: Seccion): String = File(dir, s.id + ".txt").takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()

    @Synchronized
    fun guardar(s: Seccion, texto: String) {
        dir.mkdirs()
        val f = File(dir, s.id + ".txt")
        val tmp = File(dir, s.id + ".tmp")
        tmp.writeText(texto.trim(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        if (!s.alSistema) conocimiento?.reemplazar(s.titulo, texto.trim())
    }

    fun completas(): Int = SECCIONES.count { leer(it).isNotBlank() }

    /** Estilo y temas vedados, para el mensaje de sistema. Vacío si el usuario no escribió nada. */
    fun instruccionesSistema(): String = SECCIONES.filter { it.alSistema }.mapNotNull { s ->
        leer(s).trim().takeIf { it.isNotEmpty() }?.let { "${s.titulo.uppercase()}:\n" + it.take(1500) }
    }.joinToString("\n")

    companion object {
        val SECCIONES = listOf(
            Seccion(
                "sobre_mi", "Sobre mí", "Quién sos, a qué te dedicás, qué cosas puede saber cualquiera que te escriba.",
                "Ej.: Me llamo … Estudio … en … Trabajo de … Vivo en … (solo lo que no te moleste que se sepa)", false,
            ),
            Seccion(
                "gustos", "Gustos", "Lo que te gusta y lo que no: música, comidas, deportes, series, juegos.",
                "Ej.: Me encanta … Escucho … Mi comida favorita es … No me gusta …", false,
            ),
            Seccion(
                "como_contesto", "Cómo contesto", "Tu forma de escribir: largo de los mensajes, saludos, emojis, cómo tratás a la gente.",
                "Ej.: Escribo corto y en minúscula. Saludo con «buenas». Uso pocos emojis. Trato a todos de vos.", true,
            ),
            Seccion(
                "temas_no", "Temas que no toco", "Lo que Soledad nunca debe responder: dirá que lo hablás después.",
                "Ej.: Plata y préstamos. Salud. Dónde estoy. Planes para salir. Temas de pareja.", true,
            ),
        )

        fun crear(ctx: Context) = MiInformacion(File(ctx.filesDir, "mi_informacion"), Conocimiento.crear(ctx))
    }
}
