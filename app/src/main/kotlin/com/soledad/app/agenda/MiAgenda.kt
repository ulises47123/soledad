package com.soledad.app.agenda

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/** Un evento del calendario del teléfono (ya expandido si es repetitivo). */
data class Evento(val titulo: String, val inicioMs: Long, val finMs: Long, val todoElDia: Boolean)

/** Una tarea de My Brain. vence = null si no tiene fecha. prioridad: 0 baja, 1 media, 2 alta. */
data class Tarea(val titulo: String, val venceMs: Long?, val prioridad: Int, val repetitiva: Boolean)

/**
 * Arma el texto «MI AGENDA» que recibe la IA. Lógica pura (se prueba en la JVM).
 * Solo títulos y horarios: nunca descripciones, lugares, invitados ni links.
 */
object FormatoAgenda {
    const val MAX_EVENTOS = 15
    const val MAX_TAREAS = 12
    private const val MAX_TITULO = 80
    private val DIAS = arrayOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")

    fun texto(eventos: List<Evento>, tareas: List<Tarea>?, ahora: Long, zona: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder()
        sb.append("Ahora es ").append(dia(ahora, ahora, zona)).append(' ').append(hora(ahora, zona)).append(".\n")
        val proximos = eventos.filter { it.finMs > ahora }.sortedBy { it.inicioMs }.take(MAX_EVENTOS)
        sb.append("Eventos de los próximos 7 días:")
        if (proximos.isEmpty()) sb.append(" ninguno.\n") else {
            sb.append('\n')
            for (e in proximos) {
                sb.append("- ")
                if (e.todoElDia) sb.append(dia(e.inicioMs, ahora, zona)).append(" (todo el día)")
                else {
                    val enCurso = e.inicioMs <= ahora
                    sb.append(dia(e.inicioMs, ahora, zona)).append(' ').append(hora(e.inicioMs, zona))
                        .append(" a ").append(hora(e.finMs, zona))
                    if (enCurso) sb.append(" (ahora mismo)")
                }
                sb.append(": ").append(titulo(e.titulo)).append('\n')
            }
        }
        if (tareas != null) {
            val orden = tareas.sortedWith(compareBy<Tarea>({ it.venceMs ?: Long.MAX_VALUE }, { -it.prioridad })).take(MAX_TAREAS)
            sb.append("Tareas pendientes:")
            if (orden.isEmpty()) sb.append(" ninguna.\n") else {
                sb.append('\n')
                for (t in orden) {
                    sb.append("- ").append(titulo(t.titulo))
                    val notas = ArrayList<String>()
                    t.venceMs?.let { v ->
                        notas += if (v < ahora) "vencida (era para ${dia(v, ahora, zona)})" else "para ${dia(v, ahora, zona)} ${hora(v, zona)}"
                    }
                    if (t.prioridad >= 2) notas += "importante"
                    if (t.repetitiva) notas += "se repite"
                    if (notas.isNotEmpty()) sb.append(" (").append(notas.joinToString(", ")).append(')')
                    sb.append('\n')
                }
            }
        }
        return sb.toString().trimEnd()
    }

    private fun titulo(t: String): String {
        val limpio = t.replace(Regex("\\s+"), " ").trim().ifEmpty { "(sin título)" }
        return if (limpio.length <= MAX_TITULO) limpio else limpio.take(MAX_TITULO).trimEnd() + "…"
    }

    private fun cal(ms: Long, zona: TimeZone) = Calendar.getInstance(zona).apply { timeInMillis = ms }

    private fun diaNumero(ms: Long, zona: TimeZone): Long {
        val c = cal(ms, zona)
        return (ms + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L
    }

    /** «hoy», «mañana», «ayer» o «jueves 19/11». */
    fun dia(ms: Long, ahora: Long, zona: TimeZone): String {
        val dif = diaNumero(ms, zona) - diaNumero(ahora, zona)
        return when (dif) {
            0L -> "hoy"
            1L -> "mañana"
            -1L -> "ayer"
            else -> {
                val c = cal(ms, zona)
                "%s %d/%d".format(DIAS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
            }
        }
    }

    fun hora(ms: Long, zona: TimeZone): String {
        val c = cal(ms, zona)
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}

/** Lee el calendario del teléfono y las tareas de My Brain. Nunca escribe nada. */
object MiAgenda {
    private const val TAG = "SoledadAgenda"
    private const val DIAS_ADELANTE = 7
    /** Permiso de firma que define My Brain (solo lo obtienen apps firmadas con la misma clave). */
    const val PERMISO_TAREAS = "com.mhss.app.mybrain.permission.LEER_TAREAS"
    /** Primero la versión que compilamos nosotros (debug) y después la normal. */
    val PAQUETES_MYBRAIN = listOf("com.mhss.app.mybrain.debug", "com.mhss.app.mybrain")

    fun calendarioPermitido(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun tareasPermitidas(ctx: Context) = ctx.checkSelfPermission(PERMISO_TAREAS) == PackageManager.PERMISSION_GRANTED

    fun eventos(ctx: Context, ahora: Long = System.currentTimeMillis()): List<Evento> {
        if (!calendarioPermitido(ctx)) return emptyList()
        // Desde el comienzo del día (eventos de todo el día de hoy) hasta 7 días adelante.
        val desde = ahora - 86_400_000L
        val hasta = ahora + DIAS_ADELANTE * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, desde); ContentUris.appendId(it, hasta)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val sel = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        val lista = ArrayList<Evento>()
        try {
            ctx.contentResolver.query(uri, cols, sel, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val todoElDia = c.getInt(3) == 1
                    var ini = c.getLong(1)
                    var fin = c.getLong(2)
                    if (todoElDia) {
                        // Los eventos de todo el día vienen en UTC: se pasan a la medianoche local.
                        val off = TimeZone.getDefault().getOffset(ini)
                        ini -= off; fin -= off
                    }
                    lista += Evento(c.getString(0).orEmpty(), ini, fin, todoElDia)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "calendario: ${e.javaClass.simpleName}")
        }
        return lista.distinctBy { Triple(it.titulo, it.inicioMs, it.finMs) }
    }

    /** Tareas pendientes de My Brain, o null si My Brain no está o no da permiso. */
    fun tareas(ctx: Context): List<Tarea>? {
        if (!tareasPermitidas(ctx)) return null
        for (paquete in PAQUETES_MYBRAIN) {
            val uri = Uri.parse("content://$paquete.tareas/pendientes")
            try {
                val c = ctx.contentResolver.query(uri, null, null, null, null) ?: continue
                c.use {
                    val iT = it.getColumnIndexOrThrow("titulo")
                    val iV = it.getColumnIndexOrThrow("vence")
                    val iP = it.getColumnIndexOrThrow("prioridad")
                    val iR = it.getColumnIndexOrThrow("repetitiva")
                    val lista = ArrayList<Tarea>()
                    while (it.moveToNext()) {
                        lista += Tarea(it.getString(iT).orEmpty(), if (it.isNull(iV)) null else it.getLong(iV), it.getInt(iP), it.getInt(iR) == 1)
                    }
                    return lista
                }
            } catch (e: Exception) {
                Log.w(TAG, "tareas de $paquete: ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    /** Texto para la IA según los ajustes ("" si no hay nada que pasarle). */
    fun textoParaIa(ctx: Context, usarCalendario: Boolean, usarTareas: Boolean, ahora: Long = System.currentTimeMillis()): String {
        if (!usarCalendario && !usarTareas) return ""
        val ev = if (usarCalendario && calendarioPermitido(ctx)) eventos(ctx, ahora) else null
        val ta = if (usarTareas) tareas(ctx) else null
        if (ev == null && ta == null) return ""
        return FormatoAgenda.texto(ev.orEmpty(), ta, ahora).let {
            // Si no se puede leer el calendario, no decir «ningún evento»: sería falso.
            if (ev == null) it.replace(Regex("Eventos de los próximos 7 días:[^\n]*\n?"), "") else it
        }
    }
}
