package com.soledad.app.ui

/**
 * Lógica pura de la interfaz (sin Android): colores y contraste, validaciones, el semáforo de Inicio y la lectura del
 * registro. Vive aparte para poder probarla en la JVM.
 */

// ---------- Colores y contraste (better-colors: medir el par renderizado) ----------

class Paleta(
    val nombre: String,
    val fondo: Int, val superficie: Int, val texto: Int, val tenue: Int,
    val acento: Int, val sobreAcento: Int,
    val ok: Int, val aviso: Int, val peligro: Int,
    /** Anillo de foco: debe contrastar 3:1 con fondo y superficie (WCAG 1.4.11). */
    val foco: Int,
    /** Borde de controles: 3:1 contra la superficie para que se vea el límite del control. */
    val borde: Int,
)

object Contraste {
    private fun canal(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    fun luminancia(argb: Int): Double =
        0.2126 * canal((argb shr 16) and 0xFF) + 0.7152 * canal((argb shr 8) and 0xFF) + 0.0722 * canal(argb and 0xFF)

    /** Relación WCAG 2 entre dos colores opacos. */
    fun relacion(a: Int, b: Int): Double {
        val l1 = luminancia(a); val l2 = luminancia(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}

object Paletas {
    private fun c(hex: String) = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

    /** Noche: violeta lavanda sobre azul muy oscuro (la misma familia que el ícono de la luna). */
    val SOLEDAD = Paleta(
        "Soledad", c("#0E0F1C"), c("#1A1C2E"), c("#EDEEF8"), c("#AAAEC8"),
        c("#BBAEFF"), c("#15122E"), c("#7FDCA4"), c("#F5C563"), c("#FF9A9A"), c("#FFFFFF"), c("#7F84A6"),
    )
}

// ---------- Validadores (devuelven el mensaje de error, o null si está bien) ----------

object Validar {
    /** Nombre de contacto tal como aparece en WhatsApp. */
    fun nombreContacto(n: String, existentes: List<String>): String? {
        val t = n.trim()
        return when {
            t.isEmpty() -> Textos.ERR_NOMBRE_VACIO
            t.length > 60 -> Textos.ERR_NOMBRE_LARGO
            t.contains('\n') -> Textos.ERR_NOMBRE_SALTO
            existentes.any { com.soledad.app.lista.Nombres.mismo(it, t) } -> Textos.ERR_NOMBRE_REPETIDO
            else -> null
        }
    }

    fun claveApi(c: String): String? = if (c.trim().length >= 10 && c.trim().none { it.isWhitespace() }) null else Textos.ERR_API_CLAVE
}

// ---------- Semáforo de Inicio ----------

enum class Nivel { LISTO, FALTA, AVISO }
enum class Accion { ABRIR_NOTIFICACIONES, ABRIR_SHIZUKU, PEDIR_SHIZUKU, EXCLUIR_BATERIA, IR_AJUSTES, PEDIR_AVISOS, ALARMAS_EXACTAS }

class ItemSemaforo(val id: String, val nivel: Nivel, val titulo: String, val detalle: String, val accion: Accion?, val textoAccion: String?)

class EntradaSemaforo(
    val accesoNotificaciones: Boolean,
    val shizukuInstalado: Boolean,
    val shizukuCorriendo: Boolean,
    val shizukuPermiso: Boolean,
    val bateriaExcluida: Boolean,
    val claveCargada: Boolean,
    val avisosPermitidos: Boolean,
    val alarmasExactas: Boolean,
)

object Semaforo {
    fun evaluar(e: EntradaSemaforo): List<ItemSemaforo> = listOf(
        if (e.accesoNotificaciones) ItemSemaforo("notif", Nivel.LISTO, Textos.SEM_NOTIF_TITULO, Textos.SEM_NOTIF_OK, null, null)
        else ItemSemaforo("notif", Nivel.FALTA, Textos.SEM_NOTIF_TITULO, Textos.SEM_NOTIF_FALTA, Accion.ABRIR_NOTIFICACIONES, Textos.SEM_NOTIF_ACCION),
        if (e.claveCargada) ItemSemaforo("clave", Nivel.LISTO, Textos.SEM_CLAVE_TITULO, Textos.SEM_CLAVE_OK, null, null)
        else ItemSemaforo("clave", Nivel.FALTA, Textos.SEM_CLAVE_TITULO, Textos.SEM_CLAVE_FALTA, Accion.IR_AJUSTES, Textos.SEM_CLAVE_ACCION),
        when {
            !e.shizukuInstalado -> ItemSemaforo("shizuku", Nivel.AVISO, Textos.SEM_SHIZUKU_TITULO, Textos.SEM_SHIZUKU_NO_INSTALADO, null, null)
            !e.shizukuCorriendo -> ItemSemaforo("shizuku", Nivel.AVISO, Textos.SEM_SHIZUKU_TITULO, Textos.SEM_SHIZUKU_APAGADO, Accion.ABRIR_SHIZUKU, Textos.SEM_SHIZUKU_ACCION_ABRIR)
            !e.shizukuPermiso -> ItemSemaforo("shizuku", Nivel.AVISO, Textos.SEM_SHIZUKU_TITULO, Textos.SEM_SHIZUKU_SIN_PERMISO, Accion.PEDIR_SHIZUKU, Textos.SEM_SHIZUKU_ACCION_PERMISO)
            else -> ItemSemaforo("shizuku", Nivel.LISTO, Textos.SEM_SHIZUKU_TITULO, Textos.SEM_SHIZUKU_OK, null, null)
        },
        if (e.bateriaExcluida) ItemSemaforo("bateria", Nivel.LISTO, Textos.SEM_BATERIA_TITULO, Textos.SEM_BATERIA_OK, null, null)
        else ItemSemaforo("bateria", Nivel.AVISO, Textos.SEM_BATERIA_TITULO, Textos.SEM_BATERIA_FALTA, Accion.EXCLUIR_BATERIA, Textos.SEM_BATERIA_ACCION),
        if (e.alarmasExactas) ItemSemaforo("alarmas", Nivel.LISTO, Textos.SEM_ALARMAS_TITULO, Textos.SEM_ALARMAS_OK, null, null)
        else ItemSemaforo("alarmas", Nivel.AVISO, Textos.SEM_ALARMAS_TITULO, Textos.SEM_ALARMAS_FALTA, Accion.ALARMAS_EXACTAS, Textos.SEM_ALARMAS_ACCION),
        if (e.avisosPermitidos) ItemSemaforo("avisos", Nivel.LISTO, Textos.SEM_POST_TITULO, Textos.SEM_POST_OK, null, null)
        else ItemSemaforo("avisos", Nivel.AVISO, Textos.SEM_POST_TITULO, Textos.SEM_POST_FALTA, Accion.PEDIR_AVISOS, Textos.SEM_POST_ACCION),
    )

    fun pendientes(items: List<ItemSemaforo>) = items.count { it.nivel != Nivel.LISTO }

    /** Lo imprescindible para responder: acceso a notificaciones y una clave de IA. */
    fun bloqueantes(items: List<ItemSemaforo>) = items.count { it.nivel == Nivel.FALTA }

    fun resumen(items: List<ItemSemaforo>): String =
        pendientes(items).let { if (it == 0) Textos.SEMAFORO_TODO_LISTO else Textos.semaforoFaltan(it) }

    fun simbolo(n: Nivel) = when (n) { Nivel.LISTO -> Textos.SIMBOLO_LISTO; Nivel.FALTA -> Textos.SIMBOLO_FALTA; Nivel.AVISO -> Textos.SIMBOLO_AVISO }
    fun palabra(n: Nivel) = when (n) { Nivel.LISTO -> Textos.NIVEL_LISTO; Nivel.FALTA -> Textos.NIVEL_FALTA; Nivel.AVISO -> Textos.NIVEL_AVISO }
}

// ---------- Registro ----------

class LineaRegistro(val ts: Long, val mensaje: String, val etapa: String, val detalle: String)

object LectorRegistro {
    /** Interpreta «ts | mensaje | etapa | detalle» (formato de `RegistroSqlite.ultimas`). El detalle puede contener « | ». */
    fun parsear(linea: String): LineaRegistro? {
        val p = linea.split(" | ", limit = 4)
        if (p.size < 4) return null
        val ts = p[0].trim().toLongOrNull() ?: return null
        return LineaRegistro(ts, p[1].trim(), p[2].trim(), p[3].trim())
    }

    /** Nombre y nivel de cada etapa de Soledad (el estado nunca va solo en color: símbolo + palabra). */
    fun etiqueta(etapa: String): Pair<String, Nivel> = when (etapa) {
        "respondido" -> Textos.ET_RESPONDIDO to Nivel.LISTO
        "prueba" -> Textos.ET_PRUEBA to Nivel.LISTO
        "programado" -> Textos.ET_PROGRAMADO to Nivel.AVISO
        "pendiente" -> Textos.ET_PENDIENTE to Nivel.AVISO
        "ignorado" -> Textos.ET_IGNORADO to Nivel.AVISO
        "descartado" -> Textos.ET_DESCARTADO to Nivel.AVISO
        "error" -> Textos.ET_ERROR to Nivel.FALTA
        "visita" -> Textos.ET_VISITA to Nivel.LISTO
        "chequeo" -> Textos.ET_CHEQUEO to Nivel.LISTO
        else -> Textos.ET_SISTEMA to Nivel.LISTO
    }
}

/** «en 34 min», «en 1 h 05 min», «ya». */
fun relativo(desdeMs: Long, hastaMs: Long): String {
    val min = ((hastaMs - desdeMs) / 60_000L).toInt()
    return when {
        min <= 0 -> Textos.EN_UN_MOMENTO
        min < 60 -> "en $min min"
        else -> "en ${min / 60} h ${"%02d".format(min % 60)} min"
    }
}
