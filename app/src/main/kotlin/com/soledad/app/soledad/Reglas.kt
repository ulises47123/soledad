package com.soledad.app.soledad

import kotlin.random.Random

/** Ajustes de Soledad (lo que se cambia en la pantalla Ajustes). Valores por defecto = los pedidos por el usuario. */
data class ConfigSoledad(
    /** Modo prueba: genera la respuesta y la deja en el Registro, SIN enviarla. */
    val modoPrueba: Boolean = true,
    /** Agrega «(respuesta automática)» al final. */
    val etiquetaActiva: Boolean = true,
    val etiqueta: String = ETIQUETA,
    /** Intervalos posibles entre revisiones, en minutos (cada vez se elige uno al azar). */
    val intervalosMin: List<Int> = Azar.INTERVALOS_MIN,
    /** Variación al azar ± minutos sobre el intervalo elegido. */
    val variacionMin: Int = 5,
    /** Espera al azar antes de responder, en minutos (entre 1 y 5). */
    val esperaMinMin: Int = 1,
    val esperaMaxMin: Int = 5,
    /** «Entrar un momento» a WhatsApp (solo con el teléfono desbloqueado y en la pantalla de inicio). */
    val entrarAWhatsApp: Boolean = true,
    /** Si no hay botón «Responder» en la notificación: abrir el chat y tocar Enviar (solo desbloqueado). */
    val enviarPorInterfaz: Boolean = false,
    val timeoutVerificacionMs: Long = 20_000,
) {
    companion object {
        const val ETIQUETA = "(respuesta automática)"
    }
}

/** Azar de Soledad: cuándo vuelve a mirar y cuánto espera antes de contestar. Lógica pura (se prueba en la JVM). */
object Azar {
    /** Los únicos intervalos base: 20 min, 50 min y 2 h. */
    val INTERVALOS_MIN = listOf(20, 50, 120)
    private const val MIN_MS = 60_000L

    /** Variación permitida: 0 a 10 minutos. */
    fun variacionValida(v: Int) = v.coerceIn(0, 10)

    /** Intervalos válidos elegidos por el usuario (subconjunto de 20/50/120). Si queda vacío, se usan los tres. */
    fun intervalosValidos(l: List<Int>): List<Int> = l.filter { it in INTERVALOS_MIN }.distinct().ifEmpty { INTERVALOS_MIN }

    /** Próximo intervalo en ms: uno de los intervalos al azar, ± variación al azar (nunca menos de 5 min). */
    fun proximoIntervaloMs(rnd: Random, cfg: ConfigSoledad): Long {
        val base = intervalosValidos(cfg.intervalosMin).random(rnd) * MIN_MS
        val v = variacionValida(cfg.variacionMin) * MIN_MS
        val jitter = if (v == 0L) 0L else rnd.nextLong(-v, v + 1)
        return maxOf(5 * MIN_MS, base + jitter)
    }

    /** Espera antes de responder en ms: al azar, siempre dentro de [1, 5] minutos (respeta el rango elegido). */
    fun esperaRespuestaMs(rnd: Random, cfg: ConfigSoledad): Long {
        val a = cfg.esperaMinMin.coerceIn(1, 5)
        val b = cfg.esperaMaxMin.coerceIn(1, 5)
        val (lo, hi) = if (a <= b) a to b else b to a
        return rnd.nextLong(lo * MIN_MS, hi * MIN_MS + 1)
    }

    /** Ventana de mensajes a considerar: el intervalo más largo posible más un margen. */
    fun ventanaMs(cfg: ConfigSoledad): Long =
        (intervalosValidos(cfg.intervalosMin).max() + variacionValida(cfg.variacionMin) + 30) * MIN_MS
}

object Etiqueta {
    /** Agrega la etiqueta al final si está activa y no estaba ya. */
    fun aplicar(texto: String, cfg: ConfigSoledad): String {
        val t = texto.trim()
        val e = cfg.etiqueta.trim()
        if (!cfg.etiquetaActiva || e.isEmpty() || t.endsWith(e)) return t
        return "$t $e"
    }
}
