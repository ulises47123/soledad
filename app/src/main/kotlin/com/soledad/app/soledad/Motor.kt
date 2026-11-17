package com.soledad.app.soledad

import com.soledad.app.nucleo.AccionEnvio
import com.soledad.app.nucleo.Enviador
import com.soledad.app.nucleo.EstadoCola
import com.soledad.app.nucleo.IaConRag
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Registro
import com.soledad.app.nucleo.VerificadorEnvio
import com.soledad.app.nucleo.VerificadorLista
import com.soledad.app.nucleo.Veredicto
import com.soledad.app.recepcion.ColaPersistente
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** De dónde salen los mensajes sin leer: la bandeja de notificaciones de WhatsApp. */
interface FuenteMensajes {
    /** Mensajes entrantes sin leer (uno por chat). null = sin acceso a las notificaciones. */
    fun sinLeer(edadMaximaMs: Long): List<Mensaje>?
    /** ¿La notificación de ese chat sigue en la bandeja? null = no se puede saber. */
    fun sigueSinLeer(chatId: String): Boolean?
    /** ¿Hay una acción «Responder» vigente para ese chat? (funciona con el teléfono bloqueado). */
    fun puedeResponderPorNotificacion(chatId: String): Boolean
}

interface Telefono {
    fun bloqueado(): Boolean
    fun shizuku(): Boolean
}

/** Alarmas. Una para el próximo chequeo y otra para la próxima respuesta programada. */
interface Planificador {
    fun programarChequeo(enMs: Long)
    fun programarRespuesta(enMs: Long)
    fun cancelarRespuesta()
    fun cancelarTodo()
}

/**
 * Desbloqueo del teléfono por Shizuku (opt-in del usuario). La credencial vive cifrada en la implementación:
 * el motor nunca la ve ni la registra.
 */
interface Desbloqueo {
    /** Hay credencial guardada y Shizuku para teclearla. */
    fun disponible(): Boolean
    /** Despierta y desbloquea; como mucho 3 intentos de credencial en total. true si quedó desbloqueado. */
    fun desbloquear(): Boolean
    /** Vuelve al inicio y apaga la pantalla: el sistema vuelve a bloquear. */
    fun volverABloquear()
}

object SinDesbloqueo : Desbloqueo {
    override fun disponible() = false
    override fun desbloquear() = false
    override fun volverABloquear() {}
}

/** «Entrar un momento» a WhatsApp. Devuelve lo que hizo (para el registro) y si entró. */
interface Visita {
    fun entrarUnMomento(): Pair<Boolean, String>
}

/** Estado que se muestra en Inicio y sobrevive a reinicios. */
interface EstadoSoledad {
    var activadaDesdeMs: Long
    var proximoChequeoMs: Long
    var ultimoChequeoMs: Long
    var ultimoResultado: String
    var ultimaVisitaMs: Long
    /** Último desbloqueo fallido: después de un fallo no se insiste por un rato (evita trabar el teléfono). */
    var desbloqueoFallidoMs: Long
}

class EstadoMemoria : EstadoSoledad {
    override var activadaDesdeMs = 0L
    override var proximoChequeoMs = 0L
    override var ultimoChequeoMs = 0L
    override var ultimoResultado = ""
    override var ultimaVisitaMs = 0L
    override var desbloqueoFallidoMs = 0L
}

/** Etapas del registro (la pantalla Registro las traduce a palabras y símbolos). */
object Etapa {
    const val CHEQUEO = "chequeo"
    const val VISITA = "visita"
    const val IGNORADO = "ignorado"
    const val PROGRAMADO = "programado"
    const val PRUEBA = "prueba"
    const val RESPONDIDO = "respondido"
    const val PENDIENTE = "pendiente"
    const val DESCARTADO = "descartado"
    const val ERROR = "error"
    const val SISTEMA = "sistema"
}

/**
 * El ciclo de Soledad, sin Android (todo entra por interfaces para poder probarlo en la JVM):
 *  - `chequear`: a la hora sorteada mira la bandeja, filtra (lista blanca, nunca grupos) y programa cada respuesta
 *    para dentro de 1–5 minutos al azar. Después sortea la próxima revisión (20 min, 50 min o 2 h, ± variación).
 *  - `responderPendientes`: cuando vence la espera, redacta con la IA y responde por «Responder» de la notificación
 *    (o, si el usuario lo habilitó y el teléfono está desbloqueado, abriendo el chat). Sin «Responder» y con el
 *    teléfono bloqueado, solo desbloquea si el usuario activó «Desbloquear el celular para responder» (opt-in):
 *    desbloquea, envía y vuelve a bloquear. Si no, o si el desbloqueo falla, queda pendiente.
 *  - `procesarNuevos`: la parte de detección de `chequear`, disparada apenas llega una notificación.
 *  - `salir`: cancela alarmas y pendientes y apaga el interruptor.
 */
class MotorSoledad(
    private val config: () -> ConfigSoledad,
    private val encendida: () -> Boolean,
    private val apagar: () -> Unit,
    private val lista: VerificadorLista,
    private val cola: ColaPersistente,
    private val ia: IaConRag,
    private val enviador: Enviador,
    private val verificador: VerificadorEnvio,
    private val registro: Registro,
    private val fuente: FuenteMensajes,
    private val telefono: Telefono,
    private val planificador: Planificador,
    private val visita: Visita,
    private val estado: EstadoSoledad,
    private val rnd: Random = Random.Default,
    private val reloj: () -> Long = System::currentTimeMillis,
    private val desbloqueo: Desbloqueo = SinDesbloqueo,
) {
    /** Mensajes ya anotados como ignorados (para no repetir la misma línea en cada revisión). Solo en memoria. */
    private val ignoradosVistos = LinkedHashSet<String>()

    private fun primeraVezIgnorado(id: String): Boolean {
        val nuevo = ignoradosVistos.add(id)
        if (ignoradosVistos.size > 500) ignoradosVistos.remove(ignoradosVistos.first())
        return nuevo
    }

    private fun anotar(m: Mensaje?, etapa: String, detalle: String) {
        try { registro.anotar(m, etapa, detalle) } catch (_: Throwable) { }
    }

    private fun hora(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    // ---------------------------------------------------------------- chequeo

    @Synchronized
    fun chequear(): String {
        val ahora = reloj()
        if (!encendida()) {
            planificador.cancelarTodo()
            return "Soledad está apagada"
        }
        val cfg = config()
        val ventana = Azar.ventanaMs(cfg)

        val antes = fuente.sinLeer(ventana)
        val resumen: String
        if (antes == null) {
            resumen = "No pude leer las notificaciones: falta el acceso a notificaciones."
            anotar(null, Etapa.ERROR, resumen)
        } else {
            var leidos = antes
            if (cfg.entrarAWhatsApp) {
                val desbloqueado = cfg.desbloquear && telefono.bloqueado() && intentarDesbloquear(cfg, "entrar un momento a WhatsApp")
                val (entro, nota) = try { visita.entrarUnMomento() } catch (e: Throwable) { false to "no pude entrar: ${e.javaClass.simpleName}" }
                if (desbloqueado) volverABloquear()
                anotar(null, Etapa.VISITA, nota)
                if (entro) {
                    estado.ultimaVisitaMs = reloj()
                    // Lo visto antes de entrar manda (WhatsApp puede limpiar la bandeja al abrirse); se suma lo que llegó.
                    val despues = fuente.sinLeer(ventana).orEmpty()
                    val porChat = LinkedHashMap<String, Mensaje>()
                    (antes + despues).forEach { m ->
                        val prev = porChat[m.chatId]
                        if (prev == null || m.recibidoMs > prev.recibidoMs) porChat[m.chatId] = m
                    }
                    leidos = porChat.values.toList()
                }
            }
            resumen = procesarDetectados(leidos, cfg, ahora)
        }

        val proximo = reloj() + Azar.proximoIntervaloMs(rnd, cfg)
        estado.proximoChequeoMs = proximo
        estado.ultimoResultado = resumen
        // Al final: la interfaz espera este cambio para refrescar «Último resultado» y «Próxima revisión».
        estado.ultimoChequeoMs = ahora
        planificador.programarChequeo(proximo)
        reprogramarRespuestas()
        anotar(null, Etapa.CHEQUEO, "$resumen Próxima revisión: ${hora(proximo)}.")
        return resumen
    }

    /**
     * Llegó una notificación de WhatsApp: detecta y programa (con la misma espera al azar de 1–5 min).
     * No sortea revisión nueva: las revisiones al azar siguen de respaldo. Idempotente con `chequear` (la cola deduplica).
     * Devuelve cuántas respuestas nuevas programó.
     */
    @Synchronized
    fun procesarNuevos(): Int {
        if (!encendida()) return 0
        val cfg = config()
        if (!cfg.responderAlLlegar) return 0
        val leidos = fuente.sinLeer(Azar.ventanaMs(cfg)) ?: return 0
        val antes = cola.programadas().size
        procesarDetectados(leidos, cfg, reloj())
        reprogramarRespuestas()
        return cola.programadas().size - antes
    }

    private fun procesarDetectados(mensajes: List<Mensaje>, cfg: ConfigSoledad, ahora: Long): String {
        var programados = 0
        var ignorados = 0
        var nuevos = 0
        for (m in mensajes.sortedBy { it.recibidoMs }) {
            // Solo lo que llegó con Soledad encendida.
            if (m.recibidoMs < estado.activadaDesdeMs) continue
            if (cola.fila(m.id) != null) continue // ya tratado (respondido, descartado o programado)
            nuevos++
            if (m.grupo) {
                ignorados++
                if (primeraVezIgnorado(m.id)) anotar(null, Etapa.IGNORADO, "Grupo «${m.nombre}»: los grupos nunca se responden.")
                continue
            }
            lista.verChat(m)
            if (lista.evaluar(m) == Veredicto.NO_RESPONDER) {
                ignorados++
                if (primeraVezIgnorado(m.id)) anotar(null, Etapa.IGNORADO, "${m.nombre}: ${motivoLegible(lista.motivo(m))}.")
                continue
            }
            val pendiente = cola.pendienteDe(m.chatId)
            if (pendiente != null) {
                if (pendiente.mensaje.texto == m.texto) continue
                // Ya hay una respuesta programada para ese chat: se responde una sola vez, con todo junto.
                cola.actualizarTexto(pendiente.id, m.texto)
                anotar(m, Etapa.PROGRAMADO, "${m.nombre} escribió más: «${m.texto}». Sigue programada para las ${hora(pendiente.proximoMs)}.")
                continue
            }
            val cuando = ahora + Azar.esperaRespuestaMs(rnd, cfg)
            if (cola.encolarPara(m, cuando)) {
                programados++
                anotar(m, Etapa.PROGRAMADO, "${m.nombre} escribió: «${m.texto}». Respondo a las ${hora(cuando)}.")
            }
        }
        return when {
            nuevos == 0 -> "Sin mensajes nuevos."
            else -> "Mensajes nuevos: $nuevos. Para responder: $programados. Ignorados: $ignorados."
        }
    }

    private fun motivoLegible(m: String) = when {
        m.contains("fuera de la lista") -> "no está en tu lista blanca, no respondo"
        m.contains("interruptor") -> "Soledad está apagada"
        m.contains("grupo") -> "es un grupo, no respondo"
        m.contains("trivial") -> "mensaje corto o multimedia, no hace falta responder"
        m.contains("sensible") -> "toca un tema sensible (${m.substringAfter("tema sensible: ")}), no respondo"
        m.contains("límite") -> "ya le respondí varias veces en la última hora"
        m.isBlank() -> "no corresponde responder"
        else -> m
    }

    // ---------------------------------------------------------------- respuestas

    /** Procesa las respuestas cuya espera ya venció. Devuelve cuántas procesó. */
    @Synchronized
    fun responderPendientes(): Int {
        var n = 0
        while (n < 20) {
            val m = cola.tomar() ?: break
            n++
            try { responder(m) } catch (e: Throwable) {
                cola.marcar(m.id, EstadoCola.FALLIDO, "excepción: ${e.javaClass.simpleName}")
                anotar(m, Etapa.ERROR, "No pude responder a ${m.nombre}: ${e.javaClass.simpleName}.")
            }
        }
        reprogramarRespuestas()
        return n
    }

    private fun descartar(m: Mensaje, nota: String) {
        cola.marcar(m.id, EstadoCola.DESCARTADO, nota)
        anotar(m, Etapa.DESCARTADO, "${m.nombre}: $nota")
    }

    private fun responder(m: Mensaje) {
        val cfg = config()
        val ahora = reloj()
        if (!encendida()) return descartar(m, "Soledad se apagó antes de responder.")
        if (m.grupo) return descartar(m, "es un grupo: nunca se responde.")
        if (ahora - m.recibidoMs > Azar.ventanaMs(cfg)) return descartar(m, "el mensaje ya es viejo; no respondo tarde.")
        if (lista.evaluar(m) == Veredicto.NO_RESPONDER) return descartar(m, motivoLegible(lista.motivo(m)) + ".")

        // Si la notificación desapareció, lo leíste vos (salvo que la haya limpiado la propia visita de Soledad).
        val creado = cola.fila(m.id)?.creadoMs ?: ahora
        if (fuente.sigueSinLeer(m.chatId) == false && estado.ultimaVisitaMs < creado) {
            return descartar(m, "ya lo leíste vos (la notificación no está): no respondo.")
        }

        val porNotificacion = fuente.puedeResponderPorNotificacion(m.chatId)
        val porInterfaz = cfg.enviarPorInterfaz && !telefono.bloqueado() && telefono.shizuku()
        // Sin «Responder» y con el teléfono bloqueado: solo se puede si el usuario activó el desbloqueo (opt-in).
        val conDesbloqueo = !porNotificacion && !porInterfaz && telefono.bloqueado() && puedeDesbloquear(cfg)
        if (!cfg.modoPrueba && !porNotificacion && !porInterfaz && !conDesbloqueo) {
            val motivo = when {
                !telefono.bloqueado() -> "sin botón «Responder» en la notificación"
                cfg.desbloquear -> "teléfono bloqueado, sin botón «Responder» y sin poder desbloquear ahora"
                else -> "teléfono bloqueado y sin botón «Responder»; el desbloqueo está apagado en Ajustes"
            }
            val reintento = maxOf(ahora + 10 * 60_000L, estado.proximoChequeoMs)
            cola.reprogramar(m.id, reintento, "pendiente: $motivo")
            anotar(m, Etapa.PENDIENTE, "${m.nombre}: pendiente ($motivo). Reintento a las ${hora(reintento)}.")
            return
        }

        val r = ia.responder(m, guardar = !cfg.modoPrueba)
        val base = r.texto
        if (base.isNullOrBlank()) {
            cola.marcar(m.id, EstadoCola.FALLIDO, "la IA no respondió: ${r.nota}")
            anotar(m, Etapa.ERROR, "No pude redactar la respuesta para ${m.nombre}: ${r.nota}.")
            return
        }
        val texto = Etiqueta.aplicar(base, cfg)
        val via = viaDe(r.nota)

        if (cfg.modoPrueba) {
            cola.marcar(m.id, EstadoCola.DESCARTADO, "modo prueba: no se envió")
            anotar(m, Etapa.PRUEBA, "MODO PRUEBA, no enviado. A ${m.nombre} («${m.texto}») le habría respondido: «$texto» (redactó $via)")
            return
        }

        // Se desbloquea recién ahora, con el texto listo, y se vuelve a bloquear pase lo que pase.
        var desbloqueado = false
        try {
            if (conDesbloqueo) {
                desbloqueado = intentarDesbloquear(cfg, "responder a ${m.nombre}")
                if (!desbloqueado) {
                    val reintento = maxOf(ahora + 10 * 60_000L, estado.proximoChequeoMs)
                    cola.reprogramar(m.id, reintento, "pendiente: no se pudo desbloquear")
                    anotar(m, Etapa.PENDIENTE, "${m.nombre}: pendiente (no se pudo desbloquear el teléfono). Reintento a las ${hora(reintento)}.")
                    return
                }
            }
            val accion = AccionEnvio.Texto(m.chatId, texto)
            val desde = reloj()
            val env = enviador.enviar(accion)
            if (!env.ok) {
                cola.marcar(m.id, EstadoCola.FALLIDO, "envío falló: ${env.nota}")
                anotar(m, Etapa.ERROR, "No pude enviar la respuesta a ${m.nombre}: ${env.nota}")
                return
            }
            cola.marcar(m.id, EstadoCola.ENVIADO, "enviado, falta confirmar")
            val ok = verificador.verificar(accion, desde, cfg.timeoutVerificacionMs)
            lista.registrarRespuesta(m.chatId)
            if (ok) {
                cola.marcar(m.id, EstadoCola.VERIFICADO, "respuesta confirmada")
                anotar(m, Etapa.RESPONDIDO, "Respondí a ${m.nombre}: «$texto» (confirmado; redactó $via).")
            } else {
                // No se reintenta: podría duplicar un mensaje que sí salió.
                cola.marcar(m.id, EstadoCola.ENVIADO, "enviado sin confirmación")
                anotar(m, Etapa.RESPONDIDO, "Respondí a ${m.nombre}: «$texto» (enviado, sin confirmación; redactó $via).")
            }
        } finally {
            if (desbloqueado) volverABloquear()
        }
    }

    /** Alias de la clave que redactó («nvidia-1», «gemini-2»…), sacado de la nota de la IA. Nunca la clave. */
    private fun viaDe(nota: String) = nota.substringAfter(" · ", "").ifBlank { "la IA" }

    private fun puedeDesbloquear(cfg: ConfigSoledad): Boolean =
        cfg.desbloquear && telefono.shizuku() && desbloqueo.disponible() && reloj() - estado.desbloqueoFallidoMs >= PAUSA_TRAS_FALLO_MS

    /** Desbloquea si el usuario lo permitió. Tras un fallo (3 intentos) no insiste durante [PAUSA_TRAS_FALLO_MS]. */
    private fun intentarDesbloquear(cfg: ConfigSoledad, para: String): Boolean {
        if (!puedeDesbloquear(cfg)) return false
        val ok = try { desbloqueo.desbloquear() } catch (_: Throwable) { false }
        if (ok) {
            anotar(null, Etapa.SISTEMA, "Desbloqueé el teléfono para $para.")
        } else {
            estado.desbloqueoFallidoMs = reloj()
            anotar(null, Etapa.ERROR, "No pude desbloquear el teléfono para $para (3 intentos). No vuelvo a intentar durante 1 hora.")
        }
        return ok
    }

    private fun volverABloquear() {
        try { desbloqueo.volverABloquear() } catch (_: Throwable) { }
        anotar(null, Etapa.SISTEMA, "Volví a bloquear el teléfono.")
    }

    /** La alarma de respuestas apunta siempre a la próxima que vence (o se cancela si no hay). */
    fun reprogramarRespuestas() {
        val t = cola.proximoProgramado()
        if (t == null) planificador.cancelarRespuesta() else planificador.programarRespuesta(maxOf(t, reloj() + 1_000))
    }

    // ---------------------------------------------------------------- encender / apagar

    /** Enciende: cuenta solo lo que llegue desde ahora y sortea la primera revisión. */
    @Synchronized
    fun activar(): Long {
        val ahora = reloj()
        estado.activadaDesdeMs = ahora
        val proximo = ahora + Azar.proximoIntervaloMs(rnd, config())
        estado.proximoChequeoMs = proximo
        estado.ultimoResultado = "Encendida. Todavía no revisó."
        planificador.programarChequeo(proximo)
        reprogramarRespuestas()
        anotar(null, Etapa.SISTEMA, "Soledad encendida. Primera revisión: ${hora(proximo)}.")
        return proximo
    }

    /** Apaga desde el interruptor: cancela alarmas y respuestas programadas. */
    @Synchronized
    fun desactivar(motivo: String = "Soledad apagada") {
        planificador.cancelarTodo()
        val n = cola.cancelarPendientes(motivo)
        estado.proximoChequeoMs = 0
        anotar(null, Etapa.SISTEMA, "$motivo." + if (n > 0) " Cancelé $n respuesta(s) programada(s)." else "")
    }

    companion object {
        const val PAUSA_TRAS_FALLO_MS = 60 * 60_000L
    }

    /** Botón Salir (parte sin Android): apaga el interruptor, cancela alarmas y pendientes. */
    @Synchronized
    fun salir() {
        apagar()
        desactivar("Salir: Soledad cerrada por completo")
    }
}
