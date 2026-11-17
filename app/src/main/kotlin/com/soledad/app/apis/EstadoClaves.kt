package com.soledad.app.apis

import com.soledad.app.lista.Almacen
import com.soledad.app.lista.Json

/** Última vez que una clave anduvo y su último error (sin la clave). */
data class EstadoClave(val okMs: Long = 0, val errorMs: Long = 0, val error: String = "")

/**
 * Estado de cada clave de IA por alias (`nvidia-1`, `gemini-2`…): para mostrar en Ajustes cuál anda y cuál falla.
 * Nunca guarda la clave: solo alias, horas y el texto del error (que los clientes arman sin la clave).
 */
class EstadoClaves(private val almacen: Almacen, private val reloj: () -> Long = System::currentTimeMillis) {
    private val datos = LinkedHashMap<String, EstadoClave>()

    init {
        try {
            (Json.leer(almacen.leer() ?: "") as? Map<*, *>)?.forEach { (k, v) ->
                val m = v as? Map<*, *> ?: return@forEach
                datos[k.toString()] = EstadoClave(
                    (m["ok"] as? Number)?.toLong() ?: 0, (m["err"] as? Number)?.toLong() ?: 0, m["msg"]?.toString().orEmpty(),
                )
            }
        } catch (_: Exception) { }
    }

    @Synchronized
    fun anotar(apiId: String, ok: Boolean, error: String = "") {
        val previo = datos[apiId] ?: EstadoClave()
        datos[apiId] = if (ok) previo.copy(okMs = reloj()) else previo.copy(errorMs = reloj(), error = error.take(160))
        guardar()
    }

    @Synchronized fun de(apiId: String): EstadoClave? = datos[apiId]

    @Synchronized fun olvidar(apiId: String) { if (datos.remove(apiId) != null) guardar() }

    /** La última novedad fue un error (más nuevo que el último OK). */
    fun fallando(apiId: String): Boolean = de(apiId)?.let { it.errorMs > it.okMs } ?: false

    private fun guardar() {
        almacen.escribir(Json.escribir(datos.mapValues { mapOf("ok" to it.value.okMs, "err" to it.value.errorMs, "msg" to it.value.error) }))
    }
}

/** Instancia única por proceso (archivo `gestor_apis/estado_claves.json`). */
object ClavesIa {
    @Volatile private var instancia: EstadoClaves? = null

    fun estado(ctx: android.content.Context): EstadoClaves = instancia ?: synchronized(this) {
        instancia ?: EstadoClaves(
            com.soledad.app.lista.AlmacenArchivo(java.io.File(ctx.applicationContext.filesDir, "gestor_apis/estado_claves.json")),
        ).also { instancia = it }
    }

    /** Últimos 4 caracteres, para reconocer la clave sin mostrarla. */
    fun fin(clave: String?): String = clave?.trim()?.takeLast(4).orEmpty()
}
