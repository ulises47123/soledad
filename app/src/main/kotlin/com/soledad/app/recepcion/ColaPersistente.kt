package com.soledad.app.recepcion

import com.soledad.app.nucleo.Cola
import com.soledad.app.nucleo.EstadoCola
import com.soledad.app.nucleo.Mensaje

/** Fila de la cola tal como se guarda. `proximoMs` = no tomar antes (backoff); `leaseHasta` = vencimiento del EN_PROCESO. */
data class FilaCola(
    val mensaje: Mensaje,
    val estado: EstadoCola,
    val intentos: Int,
    val leaseHasta: Long,
    val proximoMs: Long,
    val nota: String,
    val creadoMs: Long,
    val actualizadoMs: Long,
) {
    val id: String get() = mensaje.id
}

/** Almacenamiento mínimo. Sin lógica de estados: eso vive en [ColaPersistente], igual para SQLite y para pruebas. */
interface AlmacenCola {
    fun insertarSiNuevo(f: FilaCola): Boolean
    fun obtener(id: String): FilaCola?
    fun actualizar(f: FilaCola)
    fun listar(estados: Set<EstadoCola>): List<FilaCola>
    fun purgar(actualizadoAntesDe: Long, estados: Set<EstadoCola>): Int
}

/**
 * Lógica de la cola: dedupe por id, lease, intentos con tope y backoff exponencial, purga.
 * Toda operación es atómica respecto a las demás (un solo cerrojo): hay un listener y un worker en hilos distintos.
 */
class ColaPersistente(
    private val almacen: AlmacenCola,
    private val reloj: () -> Long = System::currentTimeMillis,
    private val leaseMs: Long = 180_000,
    private val maxIntentos: Int = 6,
    private val backoffBaseMs: Long = 5_000,
    private val backoffMaxMs: Long = 300_000,
    /** Se invoca tras encolar un mensaje nuevo (despierta al worker). */
    private val alEncolar: () -> Unit = {},
) : Cola {
    private val cerrojo = Any()

    override fun encolar(m: Mensaje): Boolean = encolarPara(m, 0L)

    /** Encola para que no se tome antes de `noAntesDe` (la espera al azar antes de responder). false si el id ya estaba. */
    fun encolarPara(m: Mensaje, noAntesDe: Long): Boolean {
        val nuevo = synchronized(cerrojo) {
            val t = reloj()
            almacen.insertarSiNuevo(FilaCola(m, EstadoCola.RECIBIDO, 0, 0, noAntesDe, "", t, t))
        }
        if (nuevo) alEncolar()
        return nuevo
    }

    fun fila(id: String): FilaCola? = synchronized(cerrojo) { almacen.obtener(id) }

    /** Respuesta todavía pendiente (RECIBIDO o EN_PROCESO) para ese chat, si hay. */
    fun pendienteDe(chatId: String): FilaCola? = synchronized(cerrojo) {
        almacen.listar(setOf(EstadoCola.RECIBIDO, EstadoCola.EN_PROCESO)).firstOrNull { it.mensaje.chatId == chatId }
    }

    /** Cambia el texto de un pendiente (llegaron más mensajes del mismo chat antes de responder). Conserva su horario. */
    fun actualizarTexto(id: String, texto: String) {
        synchronized(cerrojo) {
            val f = almacen.obtener(id) ?: return
            almacen.actualizar(f.copy(mensaje = f.mensaje.copy(texto = texto), actualizadoMs = reloj()))
        }
    }

    /** Vuelve a RECIBIDO para tomarlo en `noAntesDe` (pendiente: por ejemplo, teléfono bloqueado). Respeta el tope de intentos. */
    fun reprogramar(id: String, noAntesDe: Long, nota: String) {
        synchronized(cerrojo) {
            val f = almacen.obtener(id) ?: return
            val ahora = reloj()
            if (f.intentos >= maxIntentos) {
                almacen.actualizar(f.copy(estado = EstadoCola.FALLIDO, leaseHasta = 0, nota = "intentos agotados: $nota", actualizadoMs = ahora))
            } else {
                almacen.actualizar(f.copy(estado = EstadoCola.RECIBIDO, leaseHasta = 0, proximoMs = noAntesDe, nota = nota, actualizadoMs = ahora))
            }
        }
    }

    /** Pendientes (RECIBIDO o EN_PROCESO) ordenados por hora programada. */
    fun programadas(): List<FilaCola> = synchronized(cerrojo) {
        almacen.listar(setOf(EstadoCola.RECIBIDO, EstadoCola.EN_PROCESO)).sortedBy { maxOf(it.proximoMs, it.leaseHasta) }
    }

    /** Cuándo vence el próximo pendiente (para programar la alarma), o null si no hay ninguno. */
    fun proximoProgramado(): Long? = programadas().minOfOrNull { if (it.estado == EstadoCola.EN_PROCESO) it.leaseHasta else it.proximoMs }

    /** Descarta todo lo pendiente (botón Salir o interruptor apagado). Devuelve cuántos. */
    fun cancelarPendientes(nota: String): Int = synchronized(cerrojo) {
        val ahora = reloj()
        val l = almacen.listar(setOf(EstadoCola.RECIBIDO, EstadoCola.EN_PROCESO))
        l.forEach { almacen.actualizar(it.copy(estado = EstadoCola.DESCARTADO, leaseHasta = 0, nota = nota, actualizadoMs = ahora)) }
        l.size
    }

    override fun tomar(): Mensaje? = synchronized(cerrojo) {
        val ahora = reloj()
        val candidatos = almacen.listar(setOf(EstadoCola.RECIBIDO, EstadoCola.EN_PROCESO))
            .filter {
                (it.estado == EstadoCola.RECIBIDO && it.proximoMs <= ahora) ||
                    (it.estado == EstadoCola.EN_PROCESO && it.leaseHasta <= ahora)
            }
            .sortedBy { it.creadoMs }
        for (f in candidatos) {
            // Un EN_PROCESO vencido que ya agotó intentos es un mensaje que mata al worker: no se reintenta más.
            if (f.estado == EstadoCola.EN_PROCESO && f.intentos >= maxIntentos) {
                almacen.actualizar(f.copy(estado = EstadoCola.FALLIDO, nota = "lease vencido con intentos agotados", actualizadoMs = ahora))
                continue
            }
            almacen.actualizar(
                f.copy(estado = EstadoCola.EN_PROCESO, intentos = f.intentos + 1, leaseHasta = ahora + leaseMs, actualizadoMs = ahora),
            )
            return@synchronized f.mensaje
        }
        null
    }

    override fun marcar(id: String, estado: EstadoCola, nota: String) {
        synchronized(cerrojo) {
            val f = almacen.obtener(id) ?: return
            val ahora = reloj()
            if (estado == EstadoCola.RECIBIDO) {
                if (f.intentos >= maxIntentos) {
                    almacen.actualizar(f.copy(estado = EstadoCola.FALLIDO, leaseHasta = 0, nota = "intentos agotados: $nota", actualizadoMs = ahora))
                } else {
                    val espera = minOf(backoffMaxMs, backoffBaseMs shl (f.intentos - 1).coerceIn(0, 20))
                    almacen.actualizar(f.copy(estado = estado, leaseHasta = 0, proximoMs = ahora + espera, nota = nota, actualizadoMs = ahora))
                }
            } else {
                almacen.actualizar(f.copy(estado = estado, leaseHasta = 0, nota = nota, actualizadoMs = ahora))
            }
        }
    }

    override fun pendientes(): Int = synchronized(cerrojo) {
        almacen.listar(setOf(EstadoCola.RECIBIDO, EstadoCola.EN_PROCESO)).size
    }

    /** Borra terminados (VERIFICADO, FALLIDO, DESCARTADO) más viejos que `edadMs`. Devuelve cuántos. */
    fun purgar(edadMs: Long): Int = synchronized(cerrojo) {
        almacen.purgar(reloj() - edadMs, setOf(EstadoCola.VERIFICADO, EstadoCola.FALLIDO, EstadoCola.DESCARTADO))
    }
}
