package com.soledad.app.lista

import android.content.Context
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.VerificadorLista
import com.soledad.app.nucleo.Veredicto
import java.io.File
import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/** Guarda y sirve la configuración (YAML). Caché en memoria; `guardar` reescribe el archivo. */
class RepositorioConfig(private val almacen: Almacen, private val porDefecto: () -> String? = { null }) {
    @Volatile private var cache: ConfigLista? = null

    fun leer(): ConfigLista = cache ?: ConfigLista.desdeYaml(almacen.leer() ?: porDefecto()).also { cache = it }

    fun guardar(c: ConfigLista) {
        almacen.escribir(EscritorYaml.escribir(c))
        cache = c
    }
}

class VerificadorListaImpl(
    private val repo: RepositorioConfig,
    private val estado: EstadoLista,
    private val reloj: () -> Long = System::currentTimeMillis,
    private val zona: TimeZone = TimeZone.getDefault(),
) : VerificadorLista {

    /** chat -> (idMensaje, texto). Solo en memoria: el texto nunca se persiste. */
    private val textosPrevios = HashMap<String, ArrayDeque<Pair<String, String>>>()
    private val motivos = HashMap<String, String>()

    override fun verChat(m: Mensaje) {
        if (m.chatId.isNotBlank()) estado.vio(m.chatId, m.nombre, reloj())
    }

    override fun evaluar(m: Mensaje): Veredicto {
        val c = repo.leer()
        val ahora = reloj()
        fun no(motivo: String): Veredicto {
            synchronized(motivos) { motivos[m.chatId] = motivo }
            return Veredicto.NO_RESPONDER
        }

        if (!c.interruptorGeneral) return no("interruptor general apagado")
        if (c.horarioActivo && !enHorario(c, ahora)) return no("fuera del horario de actividad (${c.desde}-${c.hasta})")
        if (m.grupo && !c.grupos) return no("mensaje de grupo (grupos desactivados)")
        if (trivial(m.texto)) return no("mensaje trivial (reacción, multimedia, «ok» o solo emojis)")

        // Un contacto coincide por el id del chat, por el teléfono (agenda ↔ JID de WhatsApp) o, como respaldo, por el nombre.
        val enLista = c.contactos.any {
            it.id == m.chatId || Telefono.mismo(it.id, m.chatId) ||
                (m.nombre.isNotBlank() && Nombres.mismo(it.nombre, m.nombre))
        }
        if (c.modo == ModoLista.EXCLUIDOS && enLista) return no("contacto en la lista de excluidos")
        if (c.modo == ModoLista.PERMITIDOS && !enLista) return no("contacto fuera de la lista de permitidos")

        val previos = synchronized(textosPrevios) {
            val cola = textosPrevios.getOrPut(m.chatId) { ArrayDeque() }
            val anteriores = cola.filter { it.first != m.id }.map { it.second }
            if (cola.none { it.first == m.id }) {
                cola.addLast(m.id to m.texto)
                while (cola.size > 4) cola.removeFirst()
            }
            anteriores.takeLast(3)
        }
        sensible(c, listOf(m.texto) + previos)?.let { return no("tema sensible: $it") }

        if (estado.enUltimaHora(m.chatId, ahora) >= c.limiteRespuestasHora) return no("límite de ${c.limiteRespuestasHora} respuestas por hora alcanzado")
        return Veredicto.RESPONDER_IA
    }

    override fun motivo(m: Mensaje): String = synchronized(motivos) { motivos[m.chatId] } ?: ""

    override fun registrarRespuesta(chatId: String) = estado.registrar(chatId, reloj())

    private fun enHorario(c: ConfigLista, ahora: Long): Boolean {
        val cal = Calendar.getInstance(zona).apply { timeInMillis = ahora }
        val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val d = minutos(c.desde, 8 * 60)
        val h = minutos(c.hasta, 22 * 60)
        return if (d <= h) min in d until h else (min >= d || min < h)
    }

    private fun minutos(s: String, def: Int): Int {
        val p = s.split(':')
        val hh = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return def
        return hh * 60 + (p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
    }

    companion object {
        fun crear(ctx: Context): VerificadorLista = crearConjunto(ctx).first

        /** Verificador y ListaContactos comparten configuración y estado. */
        fun crearConjunto(ctx: Context): Pair<VerificadorListaImpl, ListaContactos> {
            val dir = File(ctx.filesDir, "verificacion_lista")
            val repo = RepositorioConfig(AlmacenArchivo(File(dir, "config.yaml"))) {
                try {
                    ctx.assets.open("flujo_bot/verificacion_lista/config.yaml").bufferedReader().use { it.readText() }
                } catch (_: Exception) { null }
            }
            val estado = EstadoLista(AlmacenArchivo(File(dir, "estado.json")))
            return VerificadorListaImpl(repo, estado) to ListaContactos(repo, estado)
        }

        private val PLACEHOLDERS = setOf(
            "foto", "photo", "sticker", "audio", "video", "gif", "imagen", "image", "documento", "document", "ubicacion", "location",
            "mensaje de voz", "voice message", "llamada perdida", "missed voice call", "missed video call", "llamada de voz perdida",
            "videollamada perdida", "mensaje eliminado", "this message was deleted", "se elimino este mensaje",
        )
        private val CORTAS = setOf("ok", "oki", "okey", "dale", "listo", "vale", "jaja", "jajaja", "jeje", "gracias", "grax", "si", "no", "bien", "buenisimo")

        internal fun normalizar(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        fun trivial(texto: String): Boolean {
            val t = normalizar(texto).replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (t.length < 2) return true
            return t in PLACEHOLDERS || t in CORTAS || t.startsWith("reacciono") || t.startsWith("reacted")
        }

        fun sensible(c: ConfigLista, textos: List<String>): String? {
            val todo = normalizar(textos.joinToString(" \n "))
            for (w in c.bloqueadas.map { normalizar(it.trim()) }.filter { it.isNotEmpty() }) {
                if (Regex("(^|[^a-z0-9ñ])" + Regex.escape(w) + "($|[^a-z0-9ñ])").containsMatchIn(todo)) return "menciona «$w»"
            }
            if (Regex("\\d{6,}").containsMatchIn(todo.replace(Regex("(?<=\\d)[ .-](?=\\d)"), ""))) return "contiene un número largo (¿código o dato bancario?)"
            return null
        }
    }
}
