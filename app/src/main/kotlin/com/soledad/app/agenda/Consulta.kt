package com.soledad.app.agenda

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/** El momento por el que preguntan («el sábado a la tarde»). `parte` = false si es el día entero. */
data class Ventana(val inicioMs: Long, val finMs: Long, val etiqueta: String, val parte: Boolean)

/**
 * Entiende de qué día y horario habla un mensaje, sin IA: «hoy», «mañana», «pasado mañana», días de la semana,
 * «el finde», «el 25», «25/11», «25 de noviembre», y la parte del día o la hora («a la tarde», «a las 18»).
 * Lógica pura (se prueba en la JVM). Devuelve null si el mensaje no nombra ningún momento.
 */
object LectorFecha {
    private const val H = 3_600_000L
    private const val DIA = 24 * H
    private val SEMANA = listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado")
    private val MESES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val ANTES_DE_PARTE = listOf("a la ", "por la ", "de la ", "en la ", "esta ", "a ")
    private val R_MANANA = Regex("\\bmanana\\b")
    private val R_DIA_SEMANA = Regex("\\b(domingo|lunes|martes|miercoles|jueves|viernes|sabado)s?\\b")
    private val R_FINDE = Regex("\\b(finde|fin de semana)\\b")
    private val R_FECHA_BARRA = Regex("\\b(\\d{1,2})/(\\d{1,2})\\b")
    private val R_FECHA_MES = Regex("\\b(\\d{1,2}) de (${MESES.joinToString("|")}|setiembre)\\b")
    private val R_FECHA_EL = Regex("\\bel (?:dia )?(\\d{1,2})\\b(?![:./\\d])")
    private val R_HORA_A_LAS = Regex("\\b(?:a las?|tipo(?: las?)?|eso de las?|como a las?) (\\d{1,2})(?:[:.](\\d{2}))?")
    private val R_HORA_RELOJ = Regex("\\b(\\d{1,2})[:.](\\d{2})\\b")
    private val R_HORA_HS = Regex("\\b(\\d{1,2}) ?(?:hs|horas)\\b")
    private val R_TARDE = Regex("\\b(tarde|tardecita|siesta)\\b")
    private val R_NOCHE = Regex("\\bnoche\\b")
    private val R_MEDIODIA = Regex("\\bmediodia\\b")

    fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()

    private fun inicioDelDia(ms: Long, zona: TimeZone): Calendar = Calendar.getInstance(zona).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** ¿La coincidencia viene después de «a la», «por la», «esta»…? Entonces es parte del día y no «el día de mañana». */
    private fun precedida(t: String, inicio: Int, prefijos: List<String>) = prefijos.any { t.substring(0, inicio).endsWith(it) }

    fun ventana(texto: String, ahora: Long, zona: TimeZone = TimeZone.getDefault()): Ventana? {
        val t = normalizar(texto)
        val hoy = inicioDelDia(ahora, zona)

        // ---- Parte del día u hora ----
        val mananas = R_MANANA.findAll(t).toList()
        val mananaParte = mananas.any { precedida(t, it.range.first, ANTES_DE_PARTE) } || Regex("\\btemprano\\b").containsMatchIn(t)
        val mananaDia = mananas.any { !precedida(t, it.range.first, ANTES_DE_PARTE) }
        val tarde = R_TARDE.findAll(t).any { !precedida(t, it.range.first, listOf("mas ", "buenas ", "muy ", "es ", "llego ", "tan ")) }
        val noche = R_NOCHE.findAll(t).any { !precedida(t, it.range.first, listOf("buenas ", "buena ")) }
        val mediodia = R_MEDIODIA.containsMatchIn(t)
        val hora = leerHora(t)

        // ---- Día ----
        var dias = 1
        val base: Calendar? = when {
            t.contains("pasado manana") -> (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 2) }
            Regex("\\bhoy\\b").containsMatchIn(t) || Regex("\\besta (noche|tarde|manana|siesta)\\b").containsMatchIn(t) -> hoy
            mananaDia && !t.contains("pasado manana") -> (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            R_FINDE.containsMatchIn(t) -> {
                val d = hoy.get(Calendar.DAY_OF_WEEK)
                when (d) {
                    Calendar.SUNDAY -> hoy
                    else -> { dias = 2; (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, Calendar.SATURDAY - d) } }
                }
            }
            R_DIA_SEMANA.containsMatchIn(t) -> {
                val pedido = SEMANA.indexOf(R_DIA_SEMANA.find(t)!!.groupValues[1]) + 1
                var dif = (pedido - hoy.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (dif == 0 && Regex("\\b(que viene|proximo|otro)\\b").containsMatchIn(t)) dif = 7
                (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, dif) }
            }
            else -> leerFecha(t, hoy)
        }
        val hayParte = mananaParte || tarde || noche || mediodia || hora != null
        val dia = base ?: if (hayParte) hoy else return null

        val (desdeMin, hastaMin, nombreParte) = when {
            hora != null -> Triple(hora, minOf(hora + 120, 24 * 60), "a las ${reloj(hora)} (hasta las ${reloj(minOf(hora + 120, 24 * 60))})")
            mediodia -> Triple(12 * 60, 14 * 60, "al mediodía (12:00 a 14:00)")
            mananaParte -> Triple(6 * 60, 12 * 60, "por la mañana (06:00 a 12:00)")
            tarde -> Triple(13 * 60, 20 * 60, "por la tarde (13:00 a 20:00)")
            noche -> Triple(20 * 60, 24 * 60, "por la noche (20:00 a 24:00)")
            else -> Triple(0, 24 * 60, "")
        }
        val inicio = dia.timeInMillis + desdeMin * 60_000L
        val fin = if (hayParte) dia.timeInMillis + hastaMin * 60_000L
        else (dia.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, dias) }.timeInMillis
        val nombreDia = if (dias == 2 && !hayParte) {
            "el fin de semana (${nombre(dia.timeInMillis, ahora, zona)} y ${nombre(dia.timeInMillis + DIA + H, ahora, zona)})"
        } else nombre(dia.timeInMillis, ahora, zona)
        return Ventana(inicio, fin, (nombreDia + " " + nombreParte).trim(), hayParte)
    }

    /** «hoy, miércoles 18/11», «mañana, jueves 19/11» o «sábado 21/11». */
    private fun nombre(ms: Long, ahora: Long, zona: TimeZone): String {
        val c = Calendar.getInstance(zona).apply { timeInMillis = ms }
        val fecha = "%s %d/%d".format(FormatoAgenda.DIAS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
        val rel = FormatoAgenda.dia(ms, ahora, zona)
        return if (rel == "hoy" || rel == "mañana") "$rel, $fecha" else fecha
    }

    private fun reloj(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** Minutos desde la medianoche, o null. «a las 6» sin más se toma como 18:00 (nadie se junta a las 6 de la mañana). */
    private fun leerHora(t: String): Int? {
        val m = R_HORA_A_LAS.find(t) ?: R_HORA_RELOJ.find(t) ?: R_HORA_HS.find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (h > 24 || min > 59) return null
        val resto = t.substring(m.range.last + 1).take(22)
        val deManana = Regex("^ ?(de la manana|am\\b|a\\.m)").containsMatchIn(resto)
        val deTarde = Regex("^ ?(de la (tarde|noche)|pm\\b|p\\.m)").containsMatchIn(resto)
        if (h < 12 && (deTarde || (!deManana && h in 1..7))) h += 12
        if (h == 24) h = 0
        return h * 60 + min
    }

    /** «25/11», «25 de noviembre» o «el 25»: la próxima vez que cae esa fecha. */
    private fun leerFecha(t: String, hoy: Calendar): Calendar? {
        var diaMes: Int
        var mes: Int? = null
        val a = R_FECHA_BARRA.find(t)
        val b = R_FECHA_MES.find(t)
        val c = R_FECHA_EL.find(t)
        when {
            a != null -> { diaMes = a.groupValues[1].toInt(); mes = a.groupValues[2].toInt() - 1 }
            b != null -> {
                diaMes = b.groupValues[1].toInt()
                mes = MESES.indexOf(b.groupValues[2].replace("setiembre", "septiembre"))
            }
            c != null -> diaMes = c.groupValues[1].toInt()
            else -> return null
        }
        if (diaMes !in 1..31 || (mes != null && mes !in 0..11)) return null
        val r = hoy.clone() as Calendar
        r.isLenient = false
        return try {
            if (mes != null) {
                r.set(Calendar.DAY_OF_MONTH, 1); r.set(Calendar.MONTH, mes); r.set(Calendar.DAY_OF_MONTH, diaMes)
                r.timeInMillis // valida
                if (r.before(hoy)) { r.add(Calendar.YEAR, 1) }
            } else {
                if (diaMes < hoy.get(Calendar.DAY_OF_MONTH)) { r.set(Calendar.DAY_OF_MONTH, 1); r.add(Calendar.MONTH, 1) }
                r.set(Calendar.DAY_OF_MONTH, diaMes)
                r.timeInMillis // valida
            }
            r
        } catch (e: Exception) { null }
    }
}

/**
 * Arma el bloque «TE PREGUNTAN POR»: qué hay en ese horario, si choca con algo y qué rato queda libre.
 * La cuenta la hace el teléfono, no la IA: así no puede equivocarse con los horarios.
 */
object Choques {
    private const val MIN_HUECO = 30 * 60_000L
    private const val H = 3_600_000L

    fun texto(v: Ventana, eventos: List<Evento>, tareas: List<Tarea>?, ahora: Long, zona: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder("TE PREGUNTAN POR: ").append(v.etiqueta).append(".\n")
        val variosDias = v.finMs - v.inicioMs > 25 * H
        val enHorario = eventos.filter { !it.todoElDia && it.inicioMs < v.finMs && it.finMs > v.inicioMs }.sortedBy { it.inicioMs }
        val todoElDia = eventos.filter { it.todoElDia && it.inicioMs < v.finMs && it.finMs > v.inicioMs }

        if (enHorario.isEmpty()) {
            sb.append("En ese horario no tenés nada anotado: estás libre.\n")
        } else {
            sb.append("En ese horario estás ocupado con:\n")
            for (e in enHorario) sb.append("- ").append(linea(e, variosDias, ahora, zona)).append('\n')
            if (!variosDias) {
                // Para el día entero solo cuentan como «ratos libres» las horas razonables (8 a 22).
                val desde = if (v.parte) v.inicioMs else v.inicioMs + 8 * H
                val hasta = if (v.parte) v.finMs else v.inicioMs + 22 * H
                val libres = huecos(desde, hasta, enHorario)
                if (libres.isEmpty()) sb.append("No te queda ningún rato libre en ese horario.\n")
                else sb.append("Te queda libre: ").append(libres.joinToString(" y ") { (a, b) ->
                    FormatoAgenda.hora(a, zona) + " a " + horaFin(b, zona)
                }).append(".\n")
            }
        }
        if (todoElDia.isNotEmpty()) {
            sb.append("Ese día además (todo el día): ").append(todoElDia.joinToString("; ") { FormatoAgenda.titulo(it.titulo) }).append(".\n")
        }
        if (v.parte) {
            val (d0, d1) = limitesDelDia(v.inicioMs, zona)
            val resto = eventos.filter { !it.todoElDia && it.inicioMs < d1 && it.finMs > d0 && it !in enHorario }.sortedBy { it.inicioMs }
            if (resto.isNotEmpty()) {
                sb.append("El resto de ese día: ").append(resto.joinToString("; ") { linea(it, false, ahora, zona) }).append(".\n")
            }
        }
        if (tareas != null) {
            val (d0, _) = limitesDelDia(v.inicioMs, zona)
            val d1 = if (variosDias) v.finMs else limitesDelDia(v.inicioMs, zona).second
            val deEseDia = tareas.filter { it.venceMs != null && it.venceMs >= d0 && it.venceMs < d1 }
            if (deEseDia.isNotEmpty()) {
                sb.append("Tareas que vencen ahí: ").append(deEseDia.joinToString("; ") { FormatoAgenda.titulo(it.titulo) }).append(".\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun linea(e: Evento, conDia: Boolean, ahora: Long, zona: TimeZone) =
        (if (conDia) FormatoAgenda.dia(e.inicioMs, ahora, zona) + " " else "") +
            FormatoAgenda.hora(e.inicioMs, zona) + " a " + horaFin(e.finMs, zona) + ": " + FormatoAgenda.titulo(e.titulo)

    /** Las 00:00 como final se leen «24:00». */
    private fun horaFin(ms: Long, zona: TimeZone) = FormatoAgenda.hora(ms, zona).let { if (it == "00:00") "24:00" else it }

    private fun limitesDelDia(ms: Long, zona: TimeZone): Pair<Long, Long> {
        val c = Calendar.getInstance(zona).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val d0 = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 1)
        return d0 to c.timeInMillis
    }

    /** Ratos de al menos media hora sin ningún evento entre `desde` y `hasta`. */
    fun huecos(desde: Long, hasta: Long, ocupados: List<Evento>): List<Pair<Long, Long>> {
        val r = ArrayList<Pair<Long, Long>>()
        var cursor = desde
        for (e in ocupados.sortedBy { it.inicioMs }) {
            if (e.inicioMs - cursor >= MIN_HUECO) r += cursor to minOf(e.inicioMs, hasta)
            cursor = maxOf(cursor, e.finMs)
            if (cursor >= hasta) break
        }
        if (hasta - cursor >= MIN_HUECO) r += cursor to hasta
        return r
    }
}

/**
 * «¿Cuándo es el cumple de Ana?»: busca por nombre entre los eventos del próximo año.
 * Lógica pura (se prueba en la JVM). Solo devuelve algo si coincide una palabra propia de la pregunta
 * (un nombre, una materia); «cumple» o «examen» solos no alcanzan, para no listar todos los cumpleaños.
 */
object Coincidencias {
    const val MAX = 4
    private val VACIAS = setOf(
        "cuando", "donde", "como", "cual", "cuales", "calendario", "agenda", "tenes", "tienes", "tengo", "tiene", "tenemos", "para", "pero",
        "esta", "este", "esto", "estas", "estos", "hola", "buenas", "sobre", "seria", "sabes", "decime", "podes", "puedo", "puede",
        "hace", "hacer", "haces", "algo", "nada", "mucho", "quiero", "quiere", "queria", "dale", "porque", "tambien", "ahora",
        "dia", "dias", "hora", "horas", "fecha", "anio", "mes", "semana", "viene", "proximo", "proxima", "anotado", "anotada",
    )
    /** Palabras que dicen el TIPO de evento: suman, pero solas no identifican a ninguno. */
    private val GENERICAS = setOf(
        "cumple", "cumpleanos", "cumpleanios", "examen", "parcial", "final", "clase", "clases", "reunion", "entrega", "turno", "feriado",
    )

    fun palabras(pregunta: String): List<String> =
        LectorFecha.normalizar(pregunta).split(Regex("[^a-z0-9ñ]+")).filter { it.length >= 4 && it !in VACIAS }.distinct()

    fun elegir(pregunta: String, eventos: List<Evento>, ahora: Long): List<Evento> {
        val buscadas = palabras(pregunta)
        val propias = buscadas.filter { it !in GENERICAS }
        if (propias.isEmpty()) return emptyList()
        val puntuados = eventos.filter { it.finMs > ahora }.mapNotNull { e ->
            val titulo = LectorFecha.normalizar(e.titulo).split(Regex("[^a-z0-9ñ]+"))
            fun esta(p: String) = titulo.any { it == p || (p.length >= 5 && it.startsWith(p)) || (it.length >= 5 && p.startsWith(it)) }
            val aciertosPropios = propias.count { esta(it) }
            if (aciertosPropios == 0) null else e to aciertosPropios * 10 + buscadas.count { it in GENERICAS && esta(it) }
        }
        val mejor = puntuados.maxOfOrNull { it.second } ?: return emptyList()
        return puntuados.filter { it.second == mejor }.map { it.first }
            .distinctBy { it.titulo to it.inicioMs }.sortedBy { it.inicioMs }.take(MAX)
    }

    /** «- sábado 20/2/2027 (todo el día): Cumpleaños de Ana Lucía» */
    fun texto(eventos: List<Evento>, ahora: Long, zona: TimeZone = TimeZone.getDefault()): String {
        if (eventos.isEmpty()) return ""
        val sb = StringBuilder("ENCONTRADO EN TU CALENDARIO (por el nombre que te preguntan):\n")
        for (e in eventos) {
            val c = Calendar.getInstance(zona).apply { timeInMillis = e.inicioMs }
            val rel = FormatoAgenda.dia(e.inicioMs, ahora, zona)
            val fecha = "%s %d/%d/%d".format(FormatoAgenda.DIAS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
            sb.append("- ").append(if (rel == "hoy" || rel == "mañana") "$rel, $fecha" else fecha)
            sb.append(if (e.todoElDia) " (todo el día)" else " " + FormatoAgenda.hora(e.inicioMs, zona) + " a " + FormatoAgenda.hora(e.finMs, zona))
            sb.append(": ").append(FormatoAgenda.titulo(e.titulo)).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

/**
 * «¿Cómo estás?»: el ánimo que anotaste en el diario de Mi cerebro. Solo el valor (1 a 5) y el día: lo que escribiste
 * en el diario nunca llega a Soledad. Lógica pura.
 */
object Animo {
    private val PREGUNTA = Regex(
        "\\b(como (estas|andas|andan|vas|venis|te sentis|te sientes|te encontras|te encuentras|amaneciste|va todo|va eso|te va|te fue|seguis|sigues)" +
            "|que tal( estas| andas| vas| todo)?\\b|todo bien|todo ok|estas (bien|mal|mejor|triste|contento|contenta)|te sentis (bien|mal|mejor)" +
            "|animo|animos|humor|andas bien|como lo llevas)"
    )
    private val NOMBRES = mapOf(5 to "muy bien", 4 to "bien", 3 to "normal, ni bien ni mal", 2 to "mal", 1 to "muy mal")

    fun preguntan(pregunta: String): Boolean = PREGUNTA.containsMatchIn(LectorFecha.normalizar(pregunta))

    /** "" si no preguntan por el ánimo, si no hay nada anotado o si lo último es de antes de ayer. */
    fun texto(pregunta: String, valor: Int?, fechaMs: Long?, ahora: Long, zona: TimeZone = TimeZone.getDefault()): String {
        if (valor == null || fechaMs == null || !preguntan(pregunta)) return ""
        val nombre = NOMBRES[valor] ?: return ""
        val dia = FormatoAgenda.dia(fechaMs, ahora, zona)
        if (fechaMs > ahora || (dia != "hoy" && dia != "ayer")) return ""
        return "TU ÁNIMO (lo último que anotaste en tu diario, $dia): $nombre"
    }
}

/** Links dentro de un texto. Lógica pura. */
object Links {
    val URL = Regex("https?://[^\\s<>\"«»]+")
    private const val COLA = ".,;:!?)]}'…"

    fun en(texto: String): List<String> = URL.findAll(texto).map { it.value.trimEnd { c -> c in COLA } }.filter { it.length > 10 }.toList()

    /**
     * Deja en la respuesta solo links que estén tal cual en `permitidos`. Uno cambiado, cortado o inventado se reemplaza por
     * el permitido al que más se parece (mismo comienzo) o se quita: nunca sale un link que no esté en las notas.
     */
    fun asegurar(respuesta: String, permitidos: List<String>): String {
        if (!respuesta.contains("http")) return respuesta
        val r = URL.replace(respuesta) { m ->
            val crudo = m.value
            val link = crudo.trimEnd { c -> c in COLA || c == '*' || c == '_' }
            val cola = crudo.substring(link.length).filter { it in COLA }
            if (link in permitidos) return@replace link + cola
            val parecido = permitidos.maxByOrNull { it.commonPrefixWith(link).length }
                ?.takeIf { it.commonPrefixWith(link).length >= minOf(20, it.length) }
            (parecido ?: "") + cola
        }
        return r.replace(Regex("\\s{2,}"), " ").replace(Regex("\\s+([.,;:!?])"), "$1").trim()
    }
}

/** Una nota de Mi cerebro (My Brain). `carpeta` = null si está suelta. */
data class NotaCerebro(val titulo: String, val contenido: String, val carpeta: String?, val modificadaMs: Long)

/**
 * «¿Tenés el cuaderno de inglés?»: elige las notas de Mi cerebro que hablan de lo que preguntan.
 * Lógica pura (se prueba en la JVM). Pesa más que la palabra esté en el título o en la carpeta que en el texto.
 */
object NotasCerebro {
    const val MAX = 3
    private const val MAX_FRAGMENTO = 420

    private fun partes(t: String) = LectorFecha.normalizar(t).split(Regex("[^a-z0-9ñ]+")).filter { it.isNotEmpty() }

    private fun esta(palabra: String, en: List<String>) =
        en.any { it == palabra || (palabra.length >= 5 && it.startsWith(palabra)) || (it.length >= 5 && palabra.startsWith(it)) }

    fun elegir(pregunta: String, notas: List<NotaCerebro>): List<NotaCerebro> {
        val buscadas = Coincidencias.palabras(pregunta)
        if (buscadas.isEmpty()) return emptyList()
        val puntuadas = notas.mapNotNull { n ->
            val cabecera = partes(n.titulo) + partes(n.carpeta.orEmpty())
            val texto = partes(n.contenido.take(3_000))
            val enCabecera = buscadas.count { esta(it, cabecera) }
            val enTexto = buscadas.count { !esta(it, cabecera) && esta(it, texto) }
            // Una sola palabra perdida en el texto de una nota larga no alcanza.
            if (enCabecera == 0 && enTexto < 2) null else n to enCabecera * 3 + enTexto
        }
        return puntuadas.sortedWith(compareByDescending<Pair<NotaCerebro, Int>> { it.second }.thenByDescending { it.first.modificadaMs })
            .take(MAX).map { it.first }
    }

    /**
     * Las notas elegidas, para la IA. Los links se tratan aparte: de cada nota va SOLO el link del renglón que más se parece
     * a lo que preguntan («ingles: https://…» si piden el de inglés), entero y sin tocar; de los demás renglones con link
     * se nombra el tema, nunca la dirección. Si no se sabe cuál piden, no va ninguno.
     */
    fun texto(notas: List<NotaCerebro>, pregunta: String = ""): String {
        if (notas.isEmpty()) return ""
        val buscadas = Coincidencias.palabras(pregunta)
        val sb = StringBuilder("EN TUS NOTAS DE MI CEREBRO (lo que tenés anotado sobre lo que te preguntan):\n")
        for (n in notas) {
            val conLink = ArrayList<Pair<String, String>>() // tema del renglón → link
            val sinLink = ArrayList<String>()
            for (renglon in n.contenido.lines()) {
                val links = Links.en(renglon)
                if (links.isEmpty()) { sinLink += renglon; continue }
                val tema = Links.URL.replace(renglon, " ").replace(Regex("[#*_`>\\[\\]()|]+"), " ").replace(Regex("\\s+"), " ").trim()
                    .replace(Regex("(?i)\\s*(:|=|->|\\bes)$"), "").trim()
                links.forEach { conLink += tema to it }
            }
            val puntos = conLink.map { (tema, _) -> partes(tema).let { p -> buscadas.count { esta(it, p) } } }
            val mejor = puntos.maxOrNull() ?: 0
            val pedidos = if (mejor == 0) emptyList() else conLink.filterIndexed { i, _ -> puntos[i] == mejor }.take(MAX_LINKS)
            val limpio = sinLink.joinToString(" ").replace(Regex("[#*_`>]+"), " ").replace(Regex("\\s+"), " ").trim()
            sb.append("- ")
            if (!n.carpeta.isNullOrBlank()) sb.append("[carpeta ").append(n.carpeta.trim()).append("] ")
            sb.append(FormatoAgenda.titulo(n.titulo))
            if (limpio.isNotEmpty()) sb.append(": ").append(if (limpio.length <= MAX_FRAGMENTO) limpio else limpio.take(MAX_FRAGMENTO).trimEnd() + "…")
            sb.append('\n')
            for ((tema, link) in pedidos) sb.append("  LINK PEDIDO").append(if (tema.isEmpty()) "" else " ($tema)").append(": ").append(link).append('\n')
            val otros = conLink.filter { it !in pedidos }.map { it.first }.filter { it.isNotEmpty() }.distinct()
            if (otros.isNotEmpty()) sb.append(if (pedidos.isEmpty()) "  Tiene links guardados de: " else "  Tiene además links de: ")
                .append(otros.take(12).joinToString(", "))
                .append(if (pedidos.isEmpty()) " (no dijeron cuál quieren: preguntá cuál, sin mandar ni prometer ninguno)\n" else " (no se mandan si no los piden)\n")
        }
        return sb.toString().trimEnd()
    }

    private const val MAX_LINKS = 2
}
