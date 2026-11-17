package com.soledad.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import com.soledad.app.Administracion
import com.soledad.app.nucleo.Servicios
import com.soledad.app.recepcion.Escucha
import com.soledad.app.recepcion.FilaCola
import com.soledad.app.recepcion.RegistroSqlite
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/** Todo lo que la interfaz lee del sistema y de los almacenes. Nada de esto muestra claves. */
object Datos {
    private const val PAQUETE_SHIZUKU = "moe.shizuku.privileged.api"

    fun eventos(max: Int = 150): List<LineaRegistro> =
        ((Servicios.registro as? RegistroSqlite)?.ultimas(max) ?: emptyList()).mapNotNull { LectorRegistro.parsear(it) }

    fun programadas(): List<FilaCola> = try { Servicios.cola.programadas() } catch (e: Exception) { emptyList() }

    /** Ids de API con un secreto guardado (solo mira nombres, nunca valores). */
    fun apisConSecreto(ctx: Context): Set<String> = try {
        val dir = File(ctx.filesDir, "gestor_apis")
        val nombres = File(dir, "secretos.enc.json").takeIf { it.exists() }?.let { f ->
            JSONObject(f.readText()).keys().asSequence().toSet()
        }.orEmpty()
        Administracion.apis.estado().map { it.id }.filter { "API_" + it.uppercase().replace('-', '_') in nombres }.toSet()
    } catch (e: Exception) { emptySet() }

    fun accesoNotificaciones(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(Escucha.componente(ctx))

    fun shizukuInstalado(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PAQUETE_SHIZUKU, 0); true
    } catch (e: Exception) { false }

    fun shizukuCorriendo(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun shizukuPermiso(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun bateriaExcluida(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun avisosPermitidos(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun alarmasExactas(ctx: Context): Boolean = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun claveCargada(ctx: Context): Boolean {
        val con = apisConSecreto(ctx)
        return Administracion.apis.estado().any { it.activa && it.id in con }
    }

    fun entradaSemaforo(ctx: Context) = EntradaSemaforo(
        accesoNotificaciones = accesoNotificaciones(ctx),
        shizukuInstalado = shizukuInstalado(ctx),
        shizukuCorriendo = shizukuCorriendo(),
        shizukuPermiso = shizukuPermiso(),
        bateriaExcluida = bateriaExcluida(ctx),
        claveCargada = claveCargada(ctx),
        avisosPermitidos = avisosPermitidos(ctx),
        alarmasExactas = alarmasExactas(ctx),
    )
}
