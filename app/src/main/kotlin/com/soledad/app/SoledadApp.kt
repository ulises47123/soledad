package com.soledad.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.soledad.app.apis.GestorApisAdmin
import com.soledad.app.apis.GestorApisImpl
import com.soledad.app.envio.EjecutorRegistrado
import com.soledad.app.envio.EnviadorWhatsApp
import com.soledad.app.envio.EstadoNotificaciones
import com.soledad.app.envio.RelojReal
import com.soledad.app.envio.RespuestasNotificacionWhatsApp
import com.soledad.app.envio.ShizukuShell
import com.soledad.app.envio.VerificadorWhatsApp
import com.soledad.app.lista.ListaContactos
import com.soledad.app.lista.VerificadorListaImpl
import com.soledad.app.nucleo.Servicios
import com.soledad.app.rag.Conocimiento
import com.soledad.app.rag.IaConRagImpl
import com.soledad.app.recepcion.ColaSqlite
import com.soledad.app.recepcion.RegistroSqlite
import com.soledad.app.soledad.AjustesSoledad
import com.soledad.app.soledad.EstadoPrefs
import com.soledad.app.soledad.FuenteNotificaciones
import com.soledad.app.soledad.MotorSoledad
import com.soledad.app.soledad.PlanificadorAlarmas
import com.soledad.app.soledad.ShellCondicional
import com.soledad.app.soledad.Soledad
import com.soledad.app.soledad.TelefonoAndroid
import com.soledad.app.soledad.VisitaShizuku
import java.io.File

/** Punto de arranque: aquí, y solo aquí, se conectan las implementaciones a sus contratos. */
class SoledadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val ctx = applicationContext
        Servicios.contexto = ctx

        sembrar(ctx, "gestor_apis/config_apis.yaml")

        Servicios.cola = ColaSqlite.crear(ctx)
        Servicios.registro = RegistroSqlite.crear(ctx)
        val (lista, contactos) = VerificadorListaImpl.crearConjunto(ctx)
        Servicios.lista = lista
        val gestor = GestorApisImpl.crear(ctx)
        Servicios.apis = gestor
        sembrarClaves(ctx, gestor)
        Servicios.ia = IaConRagImpl.crear(ctx)
        Servicios.notificaciones = RespuestasNotificacionWhatsApp(ctx)

        val ajustes = AjustesSoledad(ctx)
        val telefono = TelefonoAndroid(ctx)
        // La interfaz de WhatsApp (abrir el chat, uiautomator) solo si el usuario lo habilitó y el teléfono está desbloqueado.
        val shellInterfaz = ShellCondicional(EjecutorRegistrado(ShizukuShell()) { Log.d("Soledad/Shell", it) }) {
            ajustes.leer().enviarPorInterfaz && !telefono.bloqueado()
        }
        Servicios.enviador = EnviadorWhatsApp(shellInterfaz, EstadoNotificaciones.global, RelojReal, { Log.i("Soledad/Envio", it) })
        Servicios.verificador = VerificadorWhatsApp(shellInterfaz, anotar = { Log.i("Soledad/Verif", it) })

        Administracion.contactos = contactos
        Administracion.apis = gestor
        Administracion.conocimiento = Conocimiento.crear(ctx)

        val estado = EstadoPrefs(ctx)
        Soledad.ajustes = ajustes
        Soledad.estado = estado
        Soledad.motor = MotorSoledad(
            config = { ajustes.leer() },
            encendida = { contactos.leerConfig().interruptorGeneral },
            apagar = { contactos.setInterruptor(false) },
            lista = lista,
            cola = Servicios.cola,
            ia = Servicios.ia,
            enviador = Servicios.enviador,
            verificador = Servicios.verificador,
            registro = Servicios.registro,
            fuente = FuenteNotificaciones(),
            telefono = telefono,
            planificador = PlanificadorAlarmas(ctx),
            visita = VisitaShizuku(ctx),
            estado = estado,
        )
    }

    /** Copia un asset a filesDir si todavía no existe (así la interfaz puede editarlo sin tocar el asset). */
    private fun sembrar(ctx: Context, ruta: String) {
        val destino = File(ctx.filesDir, ruta)
        if (destino.exists()) return
        destino.parentFile?.mkdirs()
        ctx.assets.open(ruta).use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
    }

    /**
     * Compilación de prueba: las claves llegan por local.properties → BuildConfig (no se versionan) y se guardan
     * cifradas UNA vez. Si el usuario después las quita o cambia desde Ajustes, no se vuelven a sembrar.
     */
    private fun sembrarClaves(ctx: Context, gestor: GestorApisAdmin) {
        val p = ctx.getSharedPreferences("soledad_interno", Context.MODE_PRIVATE)
        if (p.getBoolean("claves_sembradas", false)) return
        try {
            val nvidia = BuildConfig.NVIDIA_API_KEY.trim()
            if (nvidia.isNotEmpty()) gestor.agregarClave("nvidia-1", "nvidia", nvidia, 300, "24h")
            BuildConfig.GEMINI_API_KEYS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, k ->
                gestor.agregarClave("gemini-${i + 1}", "gemini", k, 50, "24h")
            }
            p.edit().putBoolean("claves_sembradas", true).apply()
        } catch (e: Exception) {
            Log.w("Soledad", "no se pudieron guardar las claves de prueba (${e.javaClass.simpleName})")
        }
    }
}

/** Objetos de administración que comparte la interfaz con el flujo (mismas instancias, mismas cachés). */
object Administracion {
    lateinit var contactos: ListaContactos
    lateinit var apis: GestorApisAdmin
    lateinit var conocimiento: Conocimiento
}
