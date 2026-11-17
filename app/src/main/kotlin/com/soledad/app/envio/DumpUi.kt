package com.soledad.app.envio

import java.util.Calendar
import java.util.TimeZone

enum class EstadoMensaje(val etiqueta: String) {
    PENDIENTE("pendiente"), ENVIADO("enviado"), ENTREGADO("entregado"), LEIDO("leído");

    /** Salió del teléfono (el reloj de «pendiente» no cuenta como evidencia). */
    val salio: Boolean get() = this != PENDIENTE
}

class NodoUi(
    val indice: Int,
    val padre: Int,
    val id: String,
    val texto: String,
    val desc: String,
    val izq: Int, val arr: Int, val der: Int, val aba: Int,
    val clicable: Boolean,
) {
    val centroX get() = (izq + der) / 2
    val centroY get() = (arr + aba) / 2
}

class ArbolUi(val nodos: List<NodoUi>) {
    /** Índices de todos los descendientes de `i` (sin incluirlo). Los nodos vienen en orden de documento. */
    fun descendientes(i: Int): List<NodoUi> {
        val r = ArrayList<NodoUi>()
        val dentro = HashSet<Int>().apply { add(i) }
        for (n in nodos) {
            if (n.indice <= i) continue
            if (n.padre in dentro) { dentro.add(n.indice); r.add(n) }
        }
        return r
    }
}

class HallazgoMensaje(val estado: EstadoMensaje?, val hora: String?, val nota: String)

/** Parseo puro del XML de `uiautomator dump` y búsquedas sobre él. Sin dependencias de Android. */
object DumpUi {
    private val etiquetaNodo = Regex("""<node((?:\s+[\w:.-]+="[^"]*")*)\s*(/?)>|</node>""")
    private val atributo = Regex("""([\w:.-]+)="([^"]*)"""")
    private val limites = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private val estadoPalabra = Regex("^(pendiente|pending|enviado|sent|entregado|delivered|le[ií]do|read|visto|seen)\\b", RegexOption.IGNORE_CASE)
    private val estadoExacto = Regex("^(pendiente|pending|enviado|sent|entregado|delivered|le[ií]do|read|visto|seen)$", RegexOption.IGNORE_CASE)
    private val descImagen = Regex("^(foto|imagen|photo|image|picture)\\b", RegexOption.IGNORE_CASE)

    fun desescapar(s: String): String =
        Regex("&(#x[0-9a-fA-F]+|#\\d+|lt|gt|amp|quot|apos);").replace(s) {
            when (val e = it.groupValues[1]) {
                "lt" -> "<"; "gt" -> ">"; "amp" -> "&"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val cp = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    String(Character.toChars(cp))
                }
            }
        }

    /** null si el texto no contiene ningún nodo (dump vacío, error de uiautomator). */
    fun parsear(xml: String): ArbolUi? {
        val nodos = ArrayList<NodoUi>()
        val pila = ArrayList<Int>()
        for (m in etiquetaNodo.findAll(xml)) {
            if (m.value == "</node>") { if (pila.isNotEmpty()) pila.removeAt(pila.size - 1); continue }
            val attrs = atributo.findAll(m.groupValues[1]).associate { it.groupValues[1] to desescapar(it.groupValues[2]) }
            val b = limites.find(attrs["bounds"] ?: "")?.groupValues?.drop(1)?.map { it.toInt() } ?: listOf(0, 0, 0, 0)
            val n = NodoUi(
                nodos.size, pila.lastOrNull() ?: -1,
                attrs["resource-id"] ?: "", attrs["text"] ?: "", attrs["content-desc"] ?: "",
                b[0], b[1], b[2], b[3], attrs["clickable"] == "true",
            )
            nodos.add(n)
            if (m.groupValues[2] != "/") pila.add(n.indice)
        }
        return if (nodos.isEmpty()) null else ArbolUi(nodos)
    }

    /** Botón «Enviar» del chat o del compositor de medios. Excluye el botón de nota de voz. */
    fun botonEnviar(a: ArbolUi): NodoUi? = a.nodos.lastOrNull { n ->
        val porId = n.id.endsWith(":id/send")
        val porDesc = n.desc.trim().lowercase() in setOf("enviar", "send")
        (porId || porDesc) && n.der > n.izq && n.aba > n.arr
    }

    fun estadoDe(n: NodoUi): EstadoMensaje? {
        val d = n.desc.trim()
        val coincide = if (n.id.endsWith(":id/status")) estadoPalabra.find(d) else estadoExacto.find(d)
        return when (coincide?.groupValues?.get(1)?.lowercase()) {
            "pendiente", "pending" -> EstadoMensaje.PENDIENTE
            "enviado", "sent" -> EstadoMensaje.ENVIADO
            "entregado", "delivered" -> EstadoMensaje.ENTREGADO
            "leído", "leido", "read", "visto", "seen" -> EstadoMensaje.LEIDO
            else -> null
        }
    }

    private fun esHora(n: NodoUi) = n.id.endsWith(":id/date")

    /** Fila (burbuja) más baja que contiene un estado o una hora; así una burbuja ajena sin estado nunca hereda el de otra. */
    private fun analizarFila(a: ArbolUi, nodo: NodoUi): HallazgoMensaje {
        var actual = nodo.padre
        while (actual >= 0) {
            val d = a.descendientes(actual)
            val estados = d.filter { estadoDe(it) != null }
            val hora = d.lastOrNull { esHora(it) }?.texto?.takeIf { it.isNotBlank() }
            if (estados.isNotEmpty() || hora != null) {
                val st = estados.maxByOrNull { it.aba }?.let { estadoDe(it) }
                return HallazgoMensaje(st, hora, "fila con ${estados.size} estado(s)")
            }
            actual = a.nodos[actual].padre
        }
        return HallazgoMensaje(null, null, "sin fila con estado")
    }

    /** Último nodo (el más bajo en pantalla) cuyo texto es EXACTAMENTE el enviado. null si no aparece. */
    fun buscarTexto(a: ArbolUi, texto: String): HallazgoMensaje? {
        val c = a.nodos.filter { it.texto.isNotEmpty() && Texto.igual(texto, it.texto) }.maxByOrNull { it.aba } ?: return null
        return analizarFila(a, c)
    }

    /** Última burbuja de imagen. Con pie, exige que el pie esté dentro de la misma fila. */
    fun buscarImagen(a: ArbolUi, pie: String?): HallazgoMensaje? {
        val c = a.nodos.filter { n ->
            (n.id.endsWith(":id/image") || n.id.contains("image_message") || descImagen.containsMatchIn(n.desc.trim())) && n.aba > n.arr
        }.maxByOrNull { it.aba } ?: return null
        val h = analizarFila(a, c)
        if (pie.isNullOrBlank()) return h
        // Se busca el pie en toda la pantalla bajo la fila más cercana: se reutiliza la búsqueda de texto.
        val hp = buscarTexto(a, pie) ?: return HallazgoMensaje(null, h.hora, "imagen sin su pie")
        return if (hp.estado != null) hp else h
    }

    /** ¿La hora «H:mm» de la burbuja es del instante `desdeMs` o posterior (a minuto)? */
    fun horaCompatible(hora: String?, desdeMs: Long, zona: TimeZone): Boolean {
        if (hora == null) return true
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*([apAP])?").find(hora) ?: return true
        var h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        when (m.groupValues[3].lowercase()) {
            "p" -> if (h < 12) h += 12
            "a" -> if (h == 12) h = 0
        }
        val c = Calendar.getInstance(zona).apply { timeInMillis = desdeMs }
        val desde = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val dif = ((h * 60 + mi) - desde + 1440) % 1440
        return dif <= 120
    }
}
