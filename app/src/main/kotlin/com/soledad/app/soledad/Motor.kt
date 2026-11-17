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
}

class EstadoMemoria : EstadoSoledad {
    override var activadaDesdeMs = 0L
    override var proximoChequeoMs = 0L
    override var ultimoChequeoMs = 0L
    override var ultimoResultado = ""
    override var ultimaVisitaMs = 0L
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
 *    (o, si el usuario lo habilitó y el teléfono está desbloqueado, abriendo el chat). Si no hay forma segura de
 *    responder (teléfono bloqueado sin «Responder»), queda pendiente: NUNCA intenta desbloquear.
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
) {
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
                val (entro, nota) = try { visita.entrarUnMomento() } catch (e: Throwable) { false to "no pude entrar: ${e.javaClass.simpleName}" }
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
                anotar(null, Etapa.IGNORADO, "Grupo «${m.nombre}»: los grupos nunca se responden.")
                continue
            }
            lista.verChat(m)
            if (lista.evaluar(m) == Veredicto.NO_RESPONDER) {
                ignorados++
                anotar(null, Etapa.IGNORADO, "${m.nombre}: ${motivoLegible(lista.motivo(m))}.")
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
        if (!cfg.modoPrueba && !porNotificacion && !porInterfaz) {
            val motivo = if (telefono.bloqueado()) "teléfono bloqueado y sin botón «Responder» en la notificación"
            else "sin botón «Responder» en la notificación"
            val reintento = maxOf(ahora + 10 * 60_000L, estado.proximoChequeoMs)
            cola.reprogramar(m.id, reintento, "pendiente: $motivo")
            anotar(m, Etapa.PENDIENTE, "${m.nombre}: pendiente ($motivo). No desbloqueo el teléfono; reintento a las ${hora(reintento)}.")
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

        if (cfg.modoPrueba) {
            cola.marcar(m.id, EstadoCola.DESCARTADO, "modo prueba: no se envió")
            anotar(m, Etapa.PRUEBA, "MODO PRUEBA, no enviado. A ${m.nombre} («${m.texto}») le habría respondido: «$texto»")
            return
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
        if (ok) {
            cola.marcar(m.id, EstadoCola.VERIFICADO, "respuesta confirmada")
            lista.registrarRespuesta(m.chatId)
            anotar(m, Etapa.RESPONDIDO, "Respondí a ${m.nombre}: «$texto» (confirmado).")
        } else {
            // No se reintenta: podría duplicar un mensaje que sí salió.
            cola.marcar(m.id, EstadoCola.ENVIADO, "enviado sin confirmación")
            lista.registrarRespuesta(m.chatId)
            anotar(m, Etapa.RESPONDIDO, "Respondí a ${m.nombre}: «$texto» (enviado, sin confirmación).")
        }
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

    /** Botón Salir (parte sin Android): apaga el interruptor, cancela alarmas y pendientes. */
    @Synchronized
    fun salir() {
        apagar()
        desactivar("Salir: Soledad cerrada por completo")
    }
}
