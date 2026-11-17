package com.soledad.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.soledad.app.soledad.Soledad
import com.soledad.app.ui.Anfitrion
import com.soledad.app.ui.Nivel
import com.soledad.app.ui.Pantalla
import com.soledad.app.ui.PantallaInicio
import com.soledad.app.ui.Textos
import com.soledad.app.ui.Ui
import com.soledad.app.ui.Ui.agregar
import kotlin.concurrent.thread

/**
 * Única actividad: aloja las pantallas (Inicio, Lista blanca, Mi información, Registro, Ajustes) con una pila propia.
 * La barra superior tiene siempre a mano el botón Salir (better-layout: acciones críticas en una zona estable).
 */
class MainActivity : Activity(), Anfitrion {
    override val actividad: Activity get() = this

    private val pila = ArrayList<Pantalla>()
    private lateinit var raiz: LinearLayout
    private lateinit var barraTitulo: android.widget.TextView
    private lateinit var botonVolver: android.widget.Button
    private lateinit var estado: android.widget.TextView
    private lateinit var scroll: ScrollView
    @Volatile private var cerrada = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.aplicar(this)
        title = Textos.APP
        armarMarco()
        if (pila.isEmpty()) pila.add(PantallaInicio(this))
        mostrar()
        // Tras una actualización o un reinicio, si Soledad quedó encendida, se reprograma al abrir la app.
        try { Soledad.reanudar(this) } catch (_: Exception) { }
    }

    private fun armarMarco() {
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.p.fondo)
        }
        Ui.insets(raiz)
        val m = Ui.dp(this, Ui.MARGEN)
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 4), m, Ui.dp(this@MainActivity, 4))
        }
        botonVolver = Ui.boton(this, "‹", Ui.Estilo.SECUNDARIO, Textos.VOLVER) { volver() }
        barra.addView(botonVolver)
        barraTitulo = Ui.tituloPantalla(this, "").apply { setPadding(Ui.dp(this@MainActivity, 12), 0, 0, 0) }
        barra.addView(barraTitulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(Ui.boton(this, Textos.SALIR, Ui.Estilo.PELIGRO, Textos.SALIR_TITULO) { confirmarSalir() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(this@MainActivity, 12)
            })
        raiz.addView(barra)
        estado = Ui.zonaEstado(this)
        raiz.addView(estado)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            // La barra de desplazamiento queda visible: indica que hay más contenido debajo.
            isScrollbarFadingEnabled = false
        }
        raiz.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)
    }

    private fun confirmarSalir() {
        Ui.confirmar(this, Textos.SALIR_TITULO, Textos.SALIR_CUERPO, Textos.SALIR_CONFIRMAR) { Soledad.salir(this) }
    }

    private fun mostrar(conservarScroll: Boolean = false) {
        val actual = pila.last()
        val y = if (conservarScroll) scroll.scrollY else 0
        val contenido = actual.construir()
        val m = Ui.dp(this, Ui.MARGEN)
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, Ui.dp(this@MainActivity, 8), m, m * 2)
            agregar(contenido)
        }
        scroll.removeAllViews()
        scroll.addView(caja)
        barraTitulo.text = actual.titulo
        botonVolver.visibility = if (pila.size > 1) View.VISIBLE else View.GONE
        title = actual.titulo
        scroll.post { scroll.scrollTo(0, y) }
        if (!conservarScroll) {
            estado.text = ""
            barraTitulo.post { barraTitulo.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
        }
    }

    private fun volver() {
        if (pila.size > 1) { pila.removeAt(pila.size - 1); mostrar() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pila.size > 1) volver() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (pila.isNotEmpty()) mostrar(conservarScroll = true)
    }

    override fun onDestroy() { cerrada = true; super.onDestroy() }

    // ---------- Anfitrion ----------

    override fun abrir(p: Pantalla) { pila.add(p); mostrar() }

    override fun refrescar() { if (!cerrada) mostrar(conservarScroll = true) }

    override fun avisar(texto: String, error: Boolean) {
        estado.setTextColor(Ui.colorDe(if (error) Nivel.FALTA else Nivel.LISTO))
        // El símbolo acompaña al color: el estado no depende solo del color.
        estado.text = (if (error) "${Textos.SIMBOLO_FALTA} " else "${Textos.SIMBOLO_LISTO} ") + texto
    }

    override fun pedirPermisoAvisos() {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), CODIGO_AVISOS)
    }

    private var alContactos: ((Boolean) -> Unit)? = null

    override fun pedirPermisoContactos(alResponder: (Boolean) -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            alResponder(true); return
        }
        alContactos = alResponder
        requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), CODIGO_CONTACTOS)
    }

    override fun onRequestPermissionsResult(req: Int, permisos: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, permisos, res)
        if (req == CODIGO_CONTACTOS) {
            val cb = alContactos
            alContactos = null
            cb?.invoke(res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            return
        }
        refrescar()
    }

    override fun <T> enSegundoPlano(trabajo: () -> T, alTerminar: (Result<T>) -> Unit) {
        thread(name = "ui-trabajo") {
            val r = runCatching(trabajo)
            runOnUiThread { if (!cerrada) alTerminar(r) }
        }
    }

    companion object {
        private const val CODIGO_AVISOS = 7002
        private const val CODIGO_CONTACTOS = 7004
    }
}
