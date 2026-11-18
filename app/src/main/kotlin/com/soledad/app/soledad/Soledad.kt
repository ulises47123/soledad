package com.soledad.app.soledad

import android.app.Activity
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.soledad.app.Administracion
import com.soledad.app.MainActivity
import com.soledad.app.R
import com.soledad.app.envio.EjecutorShell
import com.soledad.app.envio.EstadoNotificaciones
import com.soledad.app.envio.ResultadoShell
import com.soledad.app.envio.ShizukuShell
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Servicios
import com.soledad.app.rag.FabricaRag
import com.soledad.app.recepcion.Escucha
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/** Ajustes persistidos (SharedPreferences). */
class AjustesSoledad(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("soledad_ajustes", Context.MODE_PRIVATE)

    fun leer(): ConfigSoledad {
        val d = ConfigSoledad()
        return ConfigSoledad(
            modoPrueba = p.getBoolean("modo_prueba", d.modoPrueba),
            etiquetaActiva = p.getBoolean("etiqueta_activa", d.etiquetaActiva),
            etiqueta = p.getString("etiqueta", d.etiqueta) ?: d.etiqueta,
            intervalosMin = p.getString("intervalos", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.let { Azar.intervalosValidos(it) } ?: d.intervalosMin,
            variacionMin = Azar.variacionValida(p.getInt("variacion", d.variacionMin)),
            alInstante = p.getBoolean("al_instante", d.alInstante),
            esperaMinMin = p.getInt("espera_min", d.esperaMinMin).coerceIn(1, 5),
            esperaMaxMin = p.getInt("espera_max", d.esperaMaxMin).coerceIn(1, 5),
            entrarAWhatsApp = p.getBoolean("entrar_whatsapp", d.entrarAWhatsApp),
            enviarPorInterfaz = p.getBoolean("enviar_interfaz", d.enviarPorInterfaz),
            responderATodos = p.getBoolean("a_todos", d.responderATodos),
            responderAlLlegar = p.getBoolean("al_llegar", d.responderAlLlegar),
            desbloquear = p.getBoolean("desbloquear", d.desbloquear),
            usarCalendario = p.getBoolean("usar_calendario", d.usarCalendario),
            usarTareas = p.getBoolean("usar_tareas", d.usarTareas),
            usarNotas = p.getBoolean("usar_notas", d.usarNotas),
            modoNegocio = p.getBoolean("modo_negocio", d.modoNegocio),
        )
    }

    fun guardar(c: ConfigSoledad) {
        p.edit()
            .putBoolean("modo_prueba", c.modoPrueba)
            .putBoolean("etiqueta_activa", c.etiquetaActiva)
            .putString("etiqueta", c.etiqueta)
            .putString("intervalos", Azar.intervalosValidos(c.intervalosMin).joinToString(","))
            .putInt("variacion", Azar.variacionValida(c.variacionMin))
            .putBoolean("al_instante", c.alInstante)
            .putInt("espera_min", c.esperaMinMin.coerceIn(1, 5))
            .putInt("espera_max", c.esperaMaxMin.coerceIn(1, 5))
            .putBoolean("entrar_whatsapp", c.entrarAWhatsApp)
            .putBoolean("enviar_interfaz", c.enviarPorInterfaz)
            .putBoolean("a_todos", c.responderATodos)
            .putBoolean("al_llegar", c.responderAlLlegar)
            .putBoolean("desbloquear", c.desbloquear)
            .putBoolean("usar_calendario", c.usarCalendario)
            .putBoolean("usar_tareas", c.usarTareas)
            .putBoolean("usar_notas", c.usarNotas)
            .putBoolean("modo_negocio", c.modoNegocio)
            .apply()
    }

    fun cambiar(f: (ConfigSoledad) -> ConfigSoledad) = guardar(f(leer()))
}

class EstadoPrefs(ctx: Context) : EstadoSoledad {
    private val p = ctx.applicationContext.getSharedPreferences("soledad_estado", Context.MODE_PRIVATE)
    override var activadaDesdeMs: Long get() = p.getLong("activada", 0); set(v) { p.edit().putLong("activada", v).apply() }
    override var proximoChequeoMs: Long get() = p.getLong("proximo", 0); set(v) { p.edit().putLong("proximo", v).apply() }
    override var ultimoChequeoMs: Long get() = p.getLong("ultimo", 0); set(v) { p.edit().putLong("ultimo", v).apply() }
    override var ultimoResultado: String get() = p.getString("resultado", "").orEmpty(); set(v) { p.edit().putString("resultado", v).apply() }
    override var ultimaVisitaMs: Long get() = p.getLong("visita", 0); set(v) { p.edit().putLong("visita", v).apply() }
    override var desbloqueoFallidoMs: Long get() = p.getLong("desbloqueo_fallido", 0); set(v) { p.edit().putLong("desbloqueo_fallido", v).apply() }
}

class FuenteNotificaciones(private val estado: EstadoNotificaciones = EstadoNotificaciones.global) : FuenteMensajes {
    override fun sinLeer(edadMaximaMs: Long): List<Mensaje>? = Escucha.instancia?.sinLeer(edadMaximaMs)
    override fun sigueSinLeer(chatId: String): Boolean? = Escucha.instancia?.chatsEnBandeja()?.contains(chatId)
    override fun puedeResponderPorNotificacion(chatId: String): Boolean = estado.hilo(chatId)?.respondedor != null
}

class TelefonoAndroid(ctx: Context, private val shell: EjecutorShell = ShizukuShell()) : Telefono {
    private val km = ctx.getSystemService(KeyguardManager::class.java)
    override fun bloqueado(): Boolean = km.isKeyguardLocked
    override fun shizuku(): Boolean = shell.disponible()
}

/** Shell que solo está «disponible» si se permite usar la interfaz (opción del usuario + teléfono desbloqueado). */
class ShellCondicional(private val interno: EjecutorShell, private val permitido: () -> Boolean) : EjecutorShell {
    override fun disponible() = permitido() && interno.disponible()
    override fun ejecutar(comando: String, limiteMs: Long, sensible: Boolean): ResultadoShell =
        if (!disponible()) ResultadoShell(-1, "interfaz no permitida ahora") else interno.ejecutar(comando, limiteMs, sensible)
}

class PlanificadorAlarmas(ctx: Context) : Planificador {
    private val c = ctx.applicationContext
    private val am = c.getSystemService(AlarmManager::class.java)

    private fun pi(accion: String, codigo: Int): PendingIntent = PendingIntent.getBroadcast(
        c, codigo, Intent(c, ReceptorAlarmas::class.java).setAction(accion),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun programar(t: Long, p: PendingIntent) {
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        }
    }

    override fun programarChequeo(enMs: Long) = programar(enMs, pi(ServicioSoledad.CHEQUEAR, 1))
    override fun programarRespuesta(enMs: Long) = programar(enMs, pi(ServicioSoledad.RESPONDER, 2))
    override fun cancelarRespuesta() = am.cancel(pi(ServicioSoledad.RESPONDER, 2))
    override fun cancelarTodo() {
        am.cancel(pi(ServicioSoledad.CHEQUEAR, 1))
        am.cancel(pi(ServicioSoledad.RESPONDER, 2))
    }
}

/**
 * «Entrar un momento» a WhatsApp por Shizuku: SOLO si la pantalla está encendida, desbloqueada y en el inicio
 * (no interrumpe si estás usando otra app y nunca desbloquea). Abre WhatsApp unos segundos y vuelve al inicio.
 */
class VisitaShizuku(ctx: Context, private val shell: EjecutorShell = ShizukuShell(), private val rnd: Random = Random.Default) : Visita {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val km = ctx.getSystemService(KeyguardManager::class.java)

    override fun entrarUnMomento(): Pair<Boolean, String> {
        if (!pm.isInteractive) return false to "Pantalla apagada: no entro a WhatsApp, solo leo las notificaciones."
        if (km.isKeyguardLocked) return false to "Teléfono bloqueado: no entro a WhatsApp, solo leo las notificaciones."
        if (!shell.disponible()) return false to "Shizuku no está activo: no entro a WhatsApp, solo leo las notificaciones."
        val inicio = shell.ejecutar("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .salida.lines().lastOrNull { it.contains('/') }?.substringBefore('/')?.trim()
        val arriba = shell.ejecutar("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").salida
        val paqueteArriba = Regex("u\\d+ ([\\w.]+)/").find(arriba)?.groupValues?.get(1)
        if (inicio == null || paqueteArriba != inicio) return false to "Estás usando el teléfono: no interrumpo, solo leo las notificaciones."
        val r = shell.ejecutar("monkey -p com.whatsapp -c android.intent.category.LAUNCHER 1")
        if (!r.ok) return false to "No pude abrir WhatsApp."
        val seg = rnd.nextInt(3, 7)
        Thread.sleep(seg * 1000L)
        shell.ejecutar("input keyevent 3")
        Thread.sleep(1_500)
        return true to "Entré a WhatsApp $seg s y volví al inicio."
    }
}

/** Punto de entrada de la app y de los servicios: encender, apagar, Salir, revisar ahora y simular. */
object Soledad {
    private const val TAG = "Soledad"
    lateinit var motor: MotorSoledad
    lateinit var ajustes: AjustesSoledad
    lateinit var estado: EstadoPrefs

    fun encendida(ctx: Context): Boolean = try { Administracion.contactos.leerConfig().interruptorGeneral } catch (e: Exception) { false }

    fun hora(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    /** Interruptor general en ON (desde la interfaz, con la app en primer plano). */
    fun activar(ctx: Context) {
        Administracion.contactos.setInterruptor(true)
        Escucha.reenlazar(ctx)
        concederConShizuku(ctx)
        motor.activar()
        ServicioSoledad.iniciar(ctx)
    }

    /** Interruptor general en OFF. */
    fun desactivar(ctx: Context) {
        Administracion.contactos.setInterruptor(false)
        motor.desactivar()
        ServicioSoledad.detener(ctx)
    }

    /** Tras reiniciar el teléfono, actualizar la app o abrirla: si estaba encendida, vuelve a programar todo. */
    fun reanudar(ctx: Context) {
        if (!encendida(ctx)) return
        // Tras actualizar la app, Android deja la escucha de notificaciones «en pausa»: se pide reenlazarla.
        Escucha.reenlazar(ctx)
        concederConShizuku(ctx)
        ServicioSoledad.iniciar(ctx)
        val ahora = System.currentTimeMillis()
        val plan = PlanificadorAlarmas(ctx)
        if (estado.proximoChequeoMs < ahora) {
            val t = ahora + 60_000L
            estado.proximoChequeoMs = t
            plan.programarChequeo(t)
        } else plan.programarChequeo(estado.proximoChequeoMs)
        motor.reprogramarRespuestas()
    }

    /**
     * Botón Salir: apaga, cancela alarmas y respuestas programadas, detiene el servicio, quita las notificaciones
     * propias, desenlaza la escucha (para que el sistema no vuelva a levantar la app), cierra la actividad y termina el proceso.
     */
    fun salir(actividad: Activity) {
        val ctx = actividad.applicationContext
        try { motor.salir() } catch (e: Throwable) { Log.e(TAG, "salir", e) }
        ServicioSoledad.detener(ctx)
        try { ctx.getSystemService(NotificationManager::class.java).cancelAll() } catch (_: Throwable) { }
        Escucha.desenlazar()
        actividad.finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }

    /** Revisión inmediata (botón «Revisar ahora»). Solo con Soledad encendida. */
    fun revisarAhora(ctx: Context): Boolean {
        if (!encendida(ctx)) return false
        ServicioSoledad.encargar(ctx, ServicioSoledad.CHEQUEAR)
        return true
    }

    /**
     * Prueba interna: un mensaje SIMULADO pasa por la misma IA (RAG + NVIDIA, respaldo Gemini) y la respuesta queda en el
     * Registro. No se envía a nadie ni toca chats ni historial. Bloquea: llamar fuera del hilo principal.
     */
    fun simular(ctx: Context, texto: String, perfil: com.soledad.app.lista.Perfil = com.soledad.app.lista.Perfil.PERSONAL): String {
        val cfg = ajustes.leer()
        val r = FabricaRag.motor(ctx).simular(texto, perfil)
        val negocio = perfil == com.soledad.app.lista.Perfil.NEGOCIO
        val respuesta = r.texto?.let { Etiqueta.aplicar(it, if (negocio) cfg.copy(etiquetaActiva = true) else cfg) }
        val detalle = if (respuesta != null) "Mensaje simulado «$texto». Habría respondido: «$respuesta» (${r.nota}). No se envió."
        else "Mensaje simulado «$texto». La IA no respondió: ${r.nota}."
        try { Servicios.registro.anotar(null, Etapa.PRUEBA, detalle) } catch (_: Throwable) { }
        return respuesta ?: "La IA no respondió: ${r.nota}"
    }

    private val alLlegarHilo = Executors.newSingleThreadExecutor()
    private val corridaEnEspera = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Llegó una notificación de WhatsApp (la llama la escucha): detecta y programa la respuesta.
     * Si está en «al instante», responde en este mismo momento (sin esperar a la alarma).
     * Funciona con el teléfono bloqueado: lee la notificación y contesta por su botón «Responder».
     */
    fun alLlegar(ctx: Context) {
        // Una ráfaga de repintados de la misma notificación deja UNA sola corrida en espera; las que llegan mientras otra
        // ya está trabajando encargan una más (antes se descartaban y el mensaje esperaba hasta la próxima revisión).
        if (!corridaEnEspera.compareAndSet(false, true)) return
        alLlegarHilo.execute {
            corridaEnEspera.set(false)
            val wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soledad:al_llegar")
            wl.acquire(4 * 60_000L)
            try {
                Thread.sleep(1_200) // deja que WhatsApp termine de repintar la notificación
                val nuevos = motor.procesarNuevos()
                if (nuevos > 0 && ajustes.leer().alInstante) motor.responderPendientes()
            } catch (e: Throwable) { Log.w(TAG, "al llegar: ${e.javaClass.simpleName}") } finally { if (wl.isHeld) wl.release() }
        }
    }

    /** Resultado de probar una clave (alias, últimos 4 caracteres, ok, detalle sin la clave). */
    class PruebaClave(val id: String, val fin: String, val ok: Boolean, val detalle: String)

    /**
     * «Probar todas»: una llamada mínima por clave, en orden, con el mismo modelo que se usa al responder.
     * No envía nada a nadie. Bloquea: llamar fuera del hilo principal.
     */
    fun probarClaves(ctx: Context): List<PruebaClave> {
        val cfg = FabricaRag.leerConfig(ctx)
        val modelos = mapOf(cfg.proveedor to cfg.modelo, cfg.proveedorRespaldo to cfg.modeloRespaldo)
        val estado = com.soledad.app.apis.ClavesIa.estado(ctx)
        return Administracion.apis.estado().map { api ->
            val clave = Administracion.apis.claveDe(api.id)
            val fin = com.soledad.app.apis.ClavesIa.fin(clave)
            if (clave == null) return@map PruebaClave(api.id, fin, false, "sin clave guardada")
            val cliente: com.soledad.app.nucleo.ClienteModelo =
                if (api.proveedor == "gemini") com.soledad.app.apis.ClienteGemini() else com.soledad.app.apis.ClienteNvidia()
            val modelo = modelos[api.proveedor] ?: if (api.proveedor == "gemini") "gemini-2.5-flash" else "nvidia/nemotron-3.5-super-vl-120b-a12b"
            val r = try {
                cliente.generar(clave, modelo, "Sos una prueba de conexión.", listOf(com.soledad.app.nucleo.TurnoChat("user", "Respondé solo: ok")), 16, 0.0)
            } catch (e: Exception) { com.soledad.app.nucleo.ResultadoModelo(null, 0, 0, e.javaClass.simpleName) }
            val ok = !r.texto.isNullOrBlank()
            estado.anotar(api.id, ok, r.error ?: "")
            PruebaClave(api.id, fin, ok, if (ok) "responde" else (r.error ?: "sin respuesta"))
        }
    }

    /** Con Shizuku: alarmas exactas y exclusión del ahorro de batería (como Racha y Límites). Silencioso si no hay Shizuku. */
    fun concederConShizuku(ctx: Context) {
        Thread {
            val sh = ShizukuShell()
            if (!sh.disponible()) return@Thread
            val p = ctx.packageName
            sh.ejecutar("appops set $p SCHEDULE_EXACT_ALARM allow")
            sh.ejecutar("dumpsys deviceidle whitelist +$p")
            sh.ejecutar("cmd notification allow_listener $p/${Escucha::class.java.name}")
        }.start()
    }
}

/** Servicio en primer plano mientras Soledad está encendida: muestra la notificación persistente y hace el trabajo. */
class ServicioSoledad : Service() {
    private val trabajo = Executors.newSingleThreadExecutor()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID_NOTIF, notificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Soledad.encendida(this) || intent?.action == DETENER) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            CHEQUEAR -> encargar {
                if (Escucha.instancia == null) { Escucha.reenlazar(this); Thread.sleep(3_000) }
                Soledad.motor.chequear()
            }
            RESPONDER -> encargar { Soledad.motor.responderPendientes() }
        }
        return START_STICKY
    }

    private fun encargar(bloque: () -> Unit) {
        trabajo.execute {
            val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soledad:trabajo")
            wl.acquire(4 * 60_000L)
            try { bloque() } catch (e: Throwable) { Log.e("Soledad", "trabajo", e) } finally {
                if (wl.isHeld) wl.release()
                try { getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notificacion()) } catch (_: Throwable) { }
            }
        }
    }

    private fun notificacion(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Soledad activa", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val prueba = try { Soledad.ajustes.leer().modoPrueba } catch (_: Throwable) { true }
        val prox = try { Soledad.estado.proximoChequeoMs } catch (_: Throwable) { 0L }
        val texto = (if (prox > 0) "Próxima revisión: ${Soledad.hora(prox)}" else "Esperando la próxima revisión") +
            if (prueba) " · modo prueba (no envía)" else ""
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Soledad está encendida")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        trabajo.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val CHEQUEAR = "com.soledad.app.CHEQUEAR"
        const val RESPONDER = "com.soledad.app.RESPONDER"
        const val DETENER = "com.soledad.app.DETENER"
        const val CANAL = "soledad_activa"
        const val ID_NOTIF = 5101

        fun iniciar(ctx: Context) {
            try { ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, ServicioSoledad::class.java)) }
            catch (e: Exception) { Log.w("Soledad", "no se pudo iniciar el servicio: ${e.javaClass.simpleName}") }
        }

        fun detener(ctx: Context) {
            ctx.applicationContext.stopService(Intent(ctx.applicationContext, ServicioSoledad::class.java))
        }

        /** Entrega una tarea al servicio. Si Android no deja levantarlo, la corre en el propio receptor (goAsync). */
        fun encargar(ctx: Context, accion: String): Boolean = try {
            ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, ServicioSoledad::class.java).setAction(accion))
            true
        } catch (e: Exception) {
            Log.w("Soledad", "servicio no permitido ahora (${e.javaClass.simpleName})")
            false
        }
    }
}

/** Alarmas de chequeo y de respuesta. */
class ReceptorAlarmas : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val accion = intent.action ?: return
        if (!Soledad.encendida(ctx)) return
        if (ServicioSoledad.encargar(ctx, accion)) return
        val pendiente = goAsync()
        Thread {
            try {
                if (accion == ServicioSoledad.CHEQUEAR) Soledad.motor.chequear() else Soledad.motor.responderPendientes()
            } catch (_: Throwable) { } finally { pendiente.finish() }
        }.start()
    }
}

/** Arranque del teléfono o actualización de la app: si Soledad estaba encendida, se reprograma. */
class ReceptorArranque : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Soledad.reanudar(ctx)
    }
}
