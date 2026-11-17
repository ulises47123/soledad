package com.soledad.app.soledad

import android.content.Context
import com.soledad.app.apis.AlmacenKeystore
import com.soledad.app.apis.AlmacenSecretos
import com.soledad.app.envio.EjecutorShell
import com.soledad.app.envio.Reloj
import com.soledad.app.envio.RelojReal
import com.soledad.app.envio.Rutas
import com.soledad.app.envio.ShizukuShell
import java.io.File

/**
 * Credencial de desbloqueo del teléfono (PIN o contraseña), cifrada con una clave AES-GCM del Android Keystore
 * (misma técnica que las claves de IA; equivalente al Jetpack Security de app-tareas-automaticas sin sumar una
 * dependencia). Nunca se escribe en logs, en el Registro, en BuildConfig ni en local.properties.
 */
class CredencialTelefono(private val secretos: AlmacenSecretos) {
    fun guardar(credencial: String) { secretos.guardar(NOMBRE, credencial) }
    fun leer(): String? = secretos.obtener(NOMBRE)?.takeIf { it.isNotEmpty() }
    fun hay(): Boolean = leer() != null
    fun borrar() { secretos.borrar(NOMBRE) }

    companion object {
        private const val NOMBRE = "CREDENCIAL_TELEFONO"
        fun crear(ctx: Context) = CredencialTelefono(AlmacenKeystore(File(ctx.applicationContext.filesDir, "seguro/credencial.enc.json")))
    }
}

/** Piezas puras de la secuencia (se prueban en la JVM). Adaptadas de app-tareas-automaticas (`puente/Dispositivo.kt`). */
object SecuenciaDesbloqueo {
    /** PIN numérico → teclas KEYCODE_0..9 (en el teclado del bloqueo `input text` no sirve); contraseña → `input text`. */
    fun comandoCredencial(credencial: String): String =
        if (credencial.isNotEmpty() && credencial.all { it in '0'..'9' })
            "input keyevent " + credencial.map { 7 + (it - '0') }.joinToString(" ")
        else "input text ${Rutas.comillas(credencial)}"

    fun deslizamiento(ancho: Int, alto: Int): String =
        "input swipe ${ancho / 2} ${(alto * 0.92).toInt()} ${ancho / 2} ${(alto * 0.12).toInt()} 350"

    /** Tamaño que entiende `input` (override si existe, si no el físico). */
    fun tamano(salidaWmSize: String): Pair<Int, Int>? {
        val m = Regex("Override size:\\s*(\\d+)x(\\d+)").find(salidaWmSize)
            ?: Regex("Physical size:\\s*(\\d+)x(\\d+)").find(salidaWmSize) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}

/**
 * Desbloqueo por Shizuku: despertar → `wm dismiss-keyguard` → credencial (PIN por teclas o contraseña por texto) →
 * Enter; respaldo deslizando hacia arriba. Como mucho [MAX_CREDENCIAL] ingresos de la credencial por llamada, para no
 * provocar el bloqueo temporal del teléfono. El comando con la credencial se marca `sensible` (no se registra).
 */
class DesbloqueoShizuku(
    private val shell: EjecutorShell,
    private val credencial: () -> String?,
    private val reloj: Reloj = RelojReal,
) : Desbloqueo {

    override fun disponible(): Boolean = !credencial().isNullOrEmpty() && shell.disponible()

    private fun encendida() = shell.ejecutar("dumpsys power | grep -m1 mWakefulness=").salida.contains("Awake")
    private fun bloqueada() = shell.ejecutar("dumpsys window | grep -m1 isKeyguardShowing").salida.contains("isKeyguardShowing=true")

    private fun despertar() {
        repeat(4) {
            if (encendida()) return
            shell.ejecutar("input keyevent 224")
            reloj.dormir(600)
        }
    }

    /** Si quedó desbloqueado, va al inicio (así «entrar un momento» no interrumpe ninguna app abierta). */
    override fun desbloquear(): Boolean {
        val ok = intentar()
        if (ok) { shell.ejecutar("input keyevent 3"); reloj.dormir(500) }
        return ok
    }

    private fun intentar(): Boolean {
        if (!shell.disponible()) return false
        despertar(); reloj.dormir(700)
        if (!bloqueada()) return true
        val c = credencial()
        val (ancho, alto) = SecuenciaDesbloqueo.tamano(shell.ejecutar("wm size").salida) ?: (720 to 1600)
        var ingresos = 0
        repeat(MAX_CREDENCIAL) {
            shell.ejecutar("wm dismiss-keyguard")
            reloj.dormir(1_200)
            if (!bloqueada()) return true
            if (!c.isNullOrEmpty() && ingresos < MAX_CREDENCIAL) {
                ingresos++
                shell.ejecutar(SecuenciaDesbloqueo.comandoCredencial(c), sensible = true)
                reloj.dormir(400)
                shell.ejecutar("input keyevent 66")
                reloj.dormir(1_300)
                if (!bloqueada()) return true
            }
            // Respaldo: deslizar hacia arriba para mostrar el campo (sin volver a teclear en esta vuelta).
            shell.ejecutar(SecuenciaDesbloqueo.deslizamiento(ancho, alto))
            reloj.dormir(900)
            if (!bloqueada()) return true
            despertar()
        }
        return !bloqueada()
    }

    override fun volverABloquear() {
        shell.ejecutar("input keyevent 3")   // inicio: no deja WhatsApp abierto
        reloj.dormir(300)
        shell.ejecutar("input keyevent 223") // apagar pantalla: el sistema vuelve a bloquear
    }

    companion object {
        const val MAX_CREDENCIAL = 3

        fun crear(ctx: Context, shell: EjecutorShell = ShizukuShell()): DesbloqueoShizuku {
            val cred = CredencialTelefono.crear(ctx)
            return DesbloqueoShizuku(shell, { cred.leer() })
        }
    }
}
