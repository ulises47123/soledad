package com.soledad.app.apis

import android.content.Context
import com.soledad.app.nucleo.EstadoApi
import com.soledad.app.nucleo.GestorApis
import com.soledad.app.nucleo.PermisoApi
import com.soledad.app.nucleo.MiniYaml
import com.soledad.app.nucleo.lista
import com.soledad.app.nucleo.mapa
import com.soledad.app.nucleo.texto
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Operaciones extra para la interfaz; el resto del bot solo ve [GestorApis]. */
interface GestorApisAdmin : GestorApis {
    /** Guarda la clave cifrada y la registra con referencia `${NOMBRE}`. Devuelve el id. */
    fun agregarClave(id: String, proveedor: String, claveEnClaro: String, limiteUsos: Int = 3, ventana: String = "24h")
    fun quitarClave(apiId: String)
    fun activar(apiId: String, activa: Boolean)
    fun reiniciarManual(apiId: String)
    /** Clave de la primera API activa del proveedor, SIN gastar un uso (para embeddings, que son baratos). */
    fun claveSinContar(proveedor: String): String?
}

/** Ventana de reinicio de cuota. */
sealed class Ventana {
    /** Fin de la ventana que empezó en [inicio]. */
    abstract fun fin(inicio: Long): Long
    class Horas(val n: Int) : Ventana() { override fun fin(inicio: Long) = inicio + n * 3_600_000L }
    object DiarioUtc : Ventana() { override fun fin(inicio: Long) = (inicio / DIA + 1) * DIA }

    companion object {
        const val DIA = 86_400_000L
        fun parsear(s: String): Ventana? {
            val t = s.trim().lowercase()
            if (t == "diario_utc") return DiarioUtc
            val m = Regex("(\\d+)h").matchEntire(t) ?: return null
            return m.groupValues[1].toInt().takeIf { it > 0 }?.let { Horas(it) }
        }
    }
}

class ConfigApi(val id: String, val proveedor: String, val clave: String, val limite: Int, val ventana: String, val activa: Boolean)

/**
 * Único dueño de las cuotas. Cada operación: bloqueo (synchronized por archivo + FileLock) → relee el estado →
 * aplica → escribe a temporal y renombra de forma atómica.
 */
class GestorApisImpl(
    private val archivoConfig: File,
    private val archivoEstado: File,
    private val secretos: AlmacenSecretos,
    private val reloj: () -> Long = { System.currentTimeMillis() },
    /** Gancho para tests: se llama con el temporal ya escrito y antes del rename. */
    private val antesDeRenombrar: () -> Unit = {},
) : GestorApisAdmin {

    private val cerrojo: Any = cerrojos.getOrPut(archivoEstado.absoluteFile.normalize().path) { Any() }

    private class Uso(var usos: Int, var inicio: Long?)
    private class Pendiente(val api: String, val inicio: Long)
    private class Estado(val apis: MutableMap<String, Uso> = LinkedHashMap(), val pendientes: MutableMap<String, Pendiente> = LinkedHashMap())

    // ---------- API pública ----------

    override fun solicitar(proveedor: String): PermisoApi? = transaccion { st, ahora ->
        for (c in leerConfig()) {
            if (!c.activa || !c.proveedor.equals(proveedor, true)) continue
            val clave = resolverClave(c.clave) ?: continue
            val u = usoVigente(st, c, ahora)
            if (u.usos >= c.limite) continue
            if (u.inicio == null) u.inicio = ahora
            u.usos++
            val token = UUID.randomUUID().toString()
            st.pendientes[token] = Pendiente(c.id, u.inicio!!)
            return@transaccion PermisoApi(c.id, clave, token)
        }
        null
    }

    override fun confirmar(permiso: PermisoApi, exito: Boolean) {
        transaccion { st, _ ->
            val p = st.pendientes.remove(permiso.token) ?: return@transaccion Unit // ya cerrado: idempotente
            if (exito) return@transaccion Unit
            val u = st.apis[p.api]
            // Si la ventana ya se reinició, el uso reservado ya no cuenta: no hay nada que devolver.
            if (u != null && u.inicio == p.inicio && u.usos > 0) {
                u.usos--
                if (u.usos == 0) u.inicio = null
            }
        }
    }

    override fun estado(): List<EstadoApi> = transaccion { st, ahora ->
        leerConfig().map { c ->
            val u = usoVigente(st, c, ahora)
            val bloqueo = if (u.usos >= c.limite && u.inicio != null) ventanaDe(c).fin(u.inicio!!) else null
            EstadoApi(c.id, c.proveedor, u.usos, c.limite, bloqueo, c.activa)
        }
    }

    override fun agregarClave(id: String, proveedor: String, claveEnClaro: String, limiteUsos: Int, ventana: String) {
        require(Regex("[A-Za-z0-9_-]+").matches(id)) { "Id no válido" }
        require(Ventana.parsear(ventana) != null) { "Ventana no válida: $ventana" }
        require(claveEnClaro.isNotBlank() && limiteUsos > 0)
        transaccion { _, _ ->
            val nombre = "API_" + id.uppercase().replace('-', '_')
            secretos.guardar(nombre, claveEnClaro.trim())
            val lista = leerConfig().filter { it.id != id } + ConfigApi(id, proveedor.lowercase(), "\${$nombre}", limiteUsos, ventana, true)
            escribirConfig(lista)
        }
    }

    override fun quitarClave(apiId: String) {
        transaccion { st, _ ->
            val c = leerConfig()
            val objetivo = c.firstOrNull { it.id == apiId } ?: return@transaccion Unit
            nombreSecreto(objetivo.clave)?.let { secretos.borrar(it) }
            escribirConfig(c.filter { it.id != apiId })
            st.apis.remove(apiId)
            st.pendientes.values.removeAll { it.api == apiId }
        }
    }

    override fun activar(apiId: String, activa: Boolean) {
        transaccion { _, _ ->
            val c = leerConfig()
            if (c.none { it.id == apiId }) return@transaccion Unit
            escribirConfig(c.map { if (it.id == apiId) ConfigApi(it.id, it.proveedor, it.clave, it.limite, it.ventana, activa) else it })
        }
    }

    override fun claveSinContar(proveedor: String): String? = synchronized(cerrojo) {
        leerConfig().firstOrNull { it.activa && it.proveedor.equals(proveedor, true) && resolverClave(it.clave) != null }
            ?.let { resolverClave(it.clave) }
    }

    override fun reiniciarManual(apiId: String) {
        transaccion { st, _ ->
            st.apis.remove(apiId)
            st.pendientes.values.removeAll { it.api == apiId }
        }
    }

    // ---------- Núcleo transaccional ----------

    private fun <T> transaccion(bloque: (Estado, Long) -> T): T = synchronized(cerrojo) {
        archivoEstado.absoluteFile.parentFile?.mkdirs()
        val archivoLock = File(archivoEstado.path + ".lock")
        RandomAccessFile(archivoLock, "rw").use { raf ->
            var cand: FileLock? = null
            val limite = System.nanoTime() + 5_000_000_000L
            while (cand == null) {
                cand = try { raf.channel.tryLock() } catch (e: java.nio.channels.OverlappingFileLockException) { null }
                if (cand == null) {
                    if (System.nanoTime() > limite) error("No se pudo bloquear el estado de cuotas")
                    Thread.sleep(10)
                }
            }
            try {
                val st = leerEstado()
                val r = bloque(st, reloj())
                guardarEstado(st)
                r
            } finally {
                cand.release()
            }
        }
    }

    private fun leerEstado(): Estado {
        val st = Estado()
        val j = try { JSONObject(archivoEstado.readText()) } catch (e: Exception) { return st }
        j.optJSONObject("apis")?.let { a ->
            a.keys().forEach { id ->
                val o = a.getJSONObject(id)
                st.apis[id] = Uso(o.optInt("usos", 0), if (o.has("inicio") && !o.isNull("inicio")) o.getLong("inicio") else null)
            }
        }
        j.optJSONObject("pendientes")?.let { p ->
            p.keys().forEach { t -> val o = p.getJSONObject(t); st.pendientes[t] = Pendiente(o.getString("api"), o.getLong("inicio")) }
        }
        return st
    }

    private fun guardarEstado(st: Estado) {
        // Un pendiente cuya ventana ya cambió no se puede reembolsar: se descarta para que el archivo no crezca.
        st.pendientes.entries.removeAll { st.apis[it.value.api]?.inicio != it.value.inicio }
        val apis = JSONObject()
        st.apis.forEach { (id, u) -> if (u.usos > 0) apis.put(id, JSONObject().put("usos", u.usos).put("inicio", u.inicio ?: JSONObject.NULL)) }
        val pend = JSONObject()
        st.pendientes.forEach { (t, p) -> pend.put(t, JSONObject().put("api", p.api).put("inicio", p.inicio)) }
        val j = JSONObject().put("version", 1).put("apis", apis).put("pendientes", pend)
        escribirAtomico(archivoEstado, j.toString(2))
    }

    private fun escribirAtomico(destino: File, contenido: String) {
        val tmp = File(destino.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            f.write(contenido.toByteArray(Charsets.UTF_8))
            f.fd.sync()
        }
        antesDeRenombrar()
        Files.move(tmp.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Devuelve el uso de la clave, reiniciándolo si su ventana ya venció. */
    private fun usoVigente(st: Estado, c: ConfigApi, ahora: Long): Uso {
        val u = st.apis.getOrPut(c.id) { Uso(0, null) }
        val ini = u.inicio
        if (ini != null && ahora >= ventanaDe(c).fin(ini)) { u.usos = 0; u.inicio = null }
        return u
    }

    private fun ventanaDe(c: ConfigApi): Ventana = Ventana.parsear(c.ventana) ?: Ventana.Horas(24)

    // ---------- Configuración ----------

    private fun leerConfig(): List<ConfigApi> {
        val raiz = try { MiniYaml.parse(archivoConfig.readText()) } catch (e: Exception) { return emptyList() }
        return raiz.mapa()["apis"].lista().mapNotNull { it ->
            val m = it.mapa()
            val id = m["id"].texto() ?: return@mapNotNull null
            ConfigApi(
                id = id,
                proveedor = (m["proveedor"].texto() ?: "gemini").lowercase(),
                clave = m["clave"].texto() ?: "",
                limite = (m["limite_usos"] as? Number)?.toInt() ?: 3,
                ventana = m["ventana_reinicio"].texto() ?: "24h",
                activa = m["activa"] as? Boolean ?: true,
            )
        }
    }

    private fun escribirConfig(lista: List<ConfigApi>) {
        val sb = StringBuilder("# Gestionado por la app (claves por referencia; el valor real está cifrado en el Keystore).\napis:\n")
        if (lista.isEmpty()) sb.setLength(sb.length - 6).also { sb.append("apis: []\n") }
        for (c in lista) {
            sb.append("  - id: ${c.id}\n    proveedor: ${c.proveedor}\n    clave: \"${c.clave}\"\n")
            sb.append("    limite_usos: ${c.limite}\n    ventana_reinicio: ${c.ventana}\n    activa: ${c.activa}\n")
        }
        archivoConfig.absoluteFile.parentFile?.mkdirs()
        escribirAtomico(archivoConfig, sb.toString())
    }

    // La llave de cierre va escapada: el motor de regex de Android (ICU) rechaza un `}` suelto y la JVM de los tests no.
    private fun nombreSecreto(ref: String): String? = Regex("\\$\\{([A-Za-z0-9_]+)\\}").matchEntire(ref.trim())?.groupValues?.get(1)

    /** Solo se aceptan referencias `${NOMBRE}`: una clave en claro dentro del YAML se ignora a propósito. */
    private fun resolverClave(ref: String): String? = nombreSecreto(ref)?.let { secretos.obtener(it) }

    companion object {
        private val cerrojos = ConcurrentHashMap<String, Any>()

        fun crear(ctx: Context): GestorApisAdmin {
            val dir = File(ctx.filesDir, "gestor_apis")
            return GestorApisImpl(
                File(dir, "config_apis.yaml"),
                File(dir, "estado_cuotas.json"),
                AlmacenKeystore(File(dir, "secretos.enc.json")),
            )
        }
    }
}
