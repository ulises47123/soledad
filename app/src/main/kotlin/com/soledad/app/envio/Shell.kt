package com.soledad.app.envio

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku as ShizukuApi

/** Resultado de un comando de shell. `ok` = terminó a tiempo con código 0. La salida mezcla stdout y stderr. */
class ResultadoShell(val codigo: Int, val salida: String, val expiro: Boolean = false) {
    val ok: Boolean get() = !expiro && codigo == 0
}

/** Todo lo que toca el celular pasa por aquí, para poder falsearlo en las pruebas JVM. */
interface EjecutorShell {
    /** Shizuku activo y con permiso para este proceso. */
    fun disponible(): Boolean

    /** `sensible` = el comando lleva un secreto (PIN): ninguna capa debe registrarlo. */
    fun ejecutar(comando: String, limiteMs: Long = 15_000, sensible: Boolean = false): ResultadoShell
}

/** Reloj inyectable: las pruebas avanzan el tiempo sin dormir de verdad. */
interface Reloj {
    fun ahora(): Long
    fun dormir(ms: Long)
}

object RelojReal : Reloj {
    override fun ahora() = System.currentTimeMillis()
    override fun dormir(ms: Long) { if (ms > 0) Thread.sleep(ms) }
}

/**
 * Shell con identidad de `shell` vía Shizuku. Mismo patrón que el proyecto `automatic`:
 * `Shizuku.newProcess` es privado en la API 13, se llama por reflexión.
 */
class ShizukuShell : EjecutorShell {
    override fun disponible(): Boolean = try {
        ShizukuApi.pingBinder() && !ShizukuApi.isPreV11() && ShizukuApi.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    private fun proceso(comando: String): Process? {
        if (!disponible()) return null
        return try {
            val m = ShizukuApi::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            m.invoke(null, arrayOf("/system/bin/sh", "-c", comando), null, null) as Process
        } catch (e: Throwable) {
            Log.e(ETIQUETA, "newProcess falló", e)
            null
        }
    }

    override fun ejecutar(comando: String, limiteMs: Long, sensible: Boolean): ResultadoShell {
        val p = proceso("{ $comando ; } 2>&1") ?: return ResultadoShell(-1, "shizuku no disponible")
        return try {
            val salida = StringBuilder()
            val lector = Thread { runCatching { p.inputStream.bufferedReader().use { salida.append(it.readText()) } } }
                .apply { isDaemon = true; start() }
            lector.join(limiteMs)
            if (lector.isAlive) {
                runCatching { p.destroy() }
                return ResultadoShell(-1, salida.toString(), expiro = true)
            }
            ResultadoShell(p.waitFor(), salida.toString().trim())
        } catch (e: Throwable) {
            ResultadoShell(-1, "error: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val ETIQUETA = "Agente/Shizuku"
        const val PAQUETE = "moe.shizuku.privileged.api"
        private const val CODIGO_PERMISO = 9101

        fun instalado(ctx: Context): Boolean =
            try { ctx.packageManager.getPackageInfo(PAQUETE, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

        fun pedirPermiso() {
            try { ShizukuApi.requestPermission(CODIGO_PERMISO) } catch (e: Throwable) { Log.e(ETIQUETA, "pedirPermiso", e) }
        }
    }
}

/**
 * Registra cada comando que pasa, salvo los marcados `sensible` (llevan el PIN): de esos solo se anota
 * que existió. El registro nunca ve el secreto aunque el comando real lo contenga.
 */
class EjecutorRegistrado(private val interno: EjecutorShell, private val log: (String) -> Unit) : EjecutorShell {
    override fun disponible() = interno.disponible()

    override fun ejecutar(comando: String, limiteMs: Long, sensible: Boolean): ResultadoShell {
        val r = interno.ejecutar(comando, limiteMs, sensible)
        log(if (sensible) "$ [comando sensible omitido] -> ${r.codigo}" else "$ ${comando.take(300)} -> ${r.codigo}")
        return r
    }
}
