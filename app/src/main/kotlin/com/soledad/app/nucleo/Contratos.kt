package com.soledad.app.nucleo

import android.content.Context

/**
 * CONTRATOS ENTRE PIEZAS. Cada interfaz tiene un único dueño (ver 02_matriz.md).
 * Regla: una pieza solo conoce a las demás por estas interfaces. No se editan aquí: si hace falta
 * cambiar algo, se pide al coordinador (la matriz anti-duplicación manda).
 */

/** Un mensaje entrante. `id` es estable (mismo mensaje → mismo id) para poder deduplicar. */
data class Mensaje(
    val id: String,
    /** Identificador del chat. Para chats individuales de WhatsApp suele ser el JID (`<número>@s.whatsapp.net`), si no, el título. */
    val chatId: String,
    val nombre: String,
    val texto: String,
    val grupo: Boolean,
    val recibidoMs: Long,
    /** com.whatsapp o com.whatsapp.w4b */
    val paquete: String,
)

enum class EstadoCola { RECIBIDO, EN_PROCESO, ENVIADO, VERIFICADO, FALLIDO, DESCARTADO }

/** Cola persistente en disco (dueño: tareas/recepcion_cola_orquestador). */
interface Cola {
    /** Persiste antes de volver. false si el id ya estaba (duplicado). */
    fun encolar(m: Mensaje): Boolean
    /** Toma el siguiente RECIBIDO, o un EN_PROCESO con lease vencido, y lo marca EN_PROCESO. null si no hay. */
    fun tomar(): Mensaje?
    fun marcar(id: String, estado: EstadoCola, nota: String = "")
    fun pendientes(): Int
}

/** Qué se va a enviar. */
sealed class AccionEnvio {
    abstract val chatId: String
    data class Texto(override val chatId: String, val texto: String) : AccionEnvio()
    /** `ruta` es un archivo local legible por el dispositivo (la elección la hace el menú dentro de su carpeta declarada). */
    data class Imagen(override val chatId: String, val ruta: String, val pie: String? = null) : AccionEnvio()
}

class ResultadoEnvio(val ok: Boolean, val nota: String)

/** Envía. NO verifica y NO decide nada (dueño: tareas/envio_verificado). */
interface Enviador {
    fun enviar(a: AccionEnvio): ResultadoEnvio
}

/** Confirma que el mensaje SALIÓ realmente (dueño: tareas/envio_verificado). `desdeMs` = instante del envío. */
interface VerificadorEnvio {
    fun verificar(a: AccionEnvio, desdeMs: Long, timeoutMs: Long): Boolean
}

enum class Veredicto { RESPONDER_IA, NO_RESPONDER }

/** Verificación de lista (dueño: tareas/verificacion_lista). Aplica interruptor, horario, grupos, modo de lista, sensibles y límites. */
interface VerificadorLista {
    /** El orquestador lo llama con CADA mensaje (antes de decidir) para recordar el chat y poder elegirlo en la interfaz. No decide nada. */
    fun verChat(m: Mensaje)
    fun evaluar(m: Mensaje): Veredicto
    /** Motivo legible de la última decisión NO_RESPONDER (para el registro). */
    fun motivo(m: Mensaje): String
    /** El orquestador lo llama cuando una respuesta automática quedó VERIFICADA (alimenta el límite por hora). */
    fun registrarRespuesta(chatId: String)
}

/**
 * Puente entre la escucha de notificaciones (recepcion) y la capa de envío (envio). La escucha lo llama con CADA
 * notificación de WhatsApp que ve —incluidas las que solo contienen mensajes propios—, ANTES de descartar nada:
 * la capa de envío guarda la acción «Responder» y detecta el eco del texto enviado (verificación por notificación).
 * Dueño de la implementación: tareas/envio_verificado.
 */
interface RespuestasNotificacion {
    fun alVer(chatId: String, nombre: String, paquete: String, notificacion: android.app.Notification)
}

class TurnoChat(val rol: String, val texto: String)
class ResultadoModelo(val texto: String?, val tokensEntrada: Int, val tokensSalida: Int, val error: String?)

/** Cliente del modelo (NVIDIA NIM o Gemini). Solo habla con la red; NO cuenta usos (eso es del GestorApis). Dueño: tareas/gestor_apis. */
interface ClienteModelo {
    fun generar(clave: String, modelo: String, sistema: String, turnos: List<TurnoChat>, maxTokens: Int, temperatura: Double): ResultadoModelo
}

/** Embeddings (NVIDIA). `tipo` = "query" para la pregunta, "passage" para la base. null = no se pudo. */
interface ClienteEmbeddings {
    fun vectores(clave: String, modelo: String, textos: List<String>, tipo: String): List<FloatArray>?
}

class Respuesta(val texto: String?, val nota: String, val tokensEntrada: Int = 0, val tokensSalida: Int = 0)

/** Paso 4 del camino normal (dueño: tareas/integracion_rag). Internamente: RAG local → pedir permiso al GestorApis → llamar al modelo. */
interface IaConRag {
    /** `guardar` = false no toca el historial del chat (modo prueba: la respuesta no se envía). */
    fun responder(m: Mensaje, guardar: Boolean = true): Respuesta
}

class PermisoApi(val apiId: String, val clave: String, val token: String)
class EstadoApi(val id: String, val proveedor: String, val usos: Int, val limite: Int, val bloqueadaHasta: Long?, val activa: Boolean)

/** Único gestor de APIs y cuotas (dueño: tareas/gestor_apis). Ningún otro módulo lleva su propia cuenta. */
interface GestorApis {
    /** Reserva (transaccionalmente) un uso de una API activa con cupo. null si no hay ninguna disponible. */
    fun solicitar(proveedor: String): PermisoApi?
    /** Igual, pero saltando las claves ya probadas en esta respuesta (respaldo clave por clave). */
    fun solicitar(proveedor: String, excluir: Set<String>): PermisoApi? = if (excluir.isEmpty()) solicitar(proveedor) else null
    /** Cierra el permiso. Si `exito` es false, el uso reservado se devuelve. */
    fun confirmar(permiso: PermisoApi, exito: Boolean)
    fun estado(): List<EstadoApi>
}

/** Registro del ciclo, con marca de tiempo e identificador del mensaje (dueño: tareas/recepcion_cola_orquestador). */
interface Registro {
    fun anotar(m: Mensaje?, etapa: String, detalle: String)
}

/** Dónde se enchufan las implementaciones. El coordinador las conecta en AgenteApp (nadie más toca este archivo). */
object Servicios {
    lateinit var cola: com.soledad.app.recepcion.ColaPersistente
    lateinit var enviador: Enviador
    lateinit var verificador: VerificadorEnvio
    lateinit var lista: VerificadorLista
    lateinit var ia: IaConRag
    lateinit var apis: GestorApis
    lateinit var notificaciones: RespuestasNotificacion
    lateinit var registro: Registro
    lateinit var contexto: Context
}
