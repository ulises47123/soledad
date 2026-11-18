package com.soledad.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Piezas de interfaz comunes. Reglas que se aplican aquí (ver estilos.md, «Skill cargada»):
 *  - radio exterior = radio interior + relleno: botones 8dp + relleno de tarjeta 12dp = tarjeta 20dp (better-ui/surfaces.md)
 *  - separación entre grupos >= 2x la de dentro del grupo: 8dp dentro, 24dp entre grupos (better-layout)
 *  - objetivos táctiles >= 48dp (better-accessibility/hit-areas.md, Material 48dp)
 *  - anillo de foco de 3dp con contraste 3:1 (better-accessibility, 1.4.11)
 *  - una sola acción rellena por vista: el botón primario; el resto con borde (better-colors «Fill exactly one action per view»)
 *  - el estado nunca va solo en color: símbolo + palabra (better-accessibility 1.4.1)
 */
object Ui {
    var p: Paleta = Paletas.SOLEDAD

    // Escala tipográfica (sp, sigue el tamaño de letra del sistema). Piso: 14sp para texto legible.
    const val T_TITULO = 24f
    const val T_SECCION = 13f
    const val T_CUERPO = 16f
    const val T_NOTA = 14f

    const val ESP_DENTRO = 8
    const val ESP_GRUPO = 24
    const val MARGEN = 16
    const val RADIO_CONTROL = 14
    const val PAD_TARJETA = 16
    const val RADIO_TARJETA = RADIO_CONTROL + PAD_TARJETA
    const val MIN_TOQUE = 48

    fun aplicar(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        p = Paletas.SOLEDAD
    }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Android 15 dibuja la app detrás de las barras del sistema y del teclado: se deja el espacio. */
    fun insets(v: View) {
        v.setOnApplyWindowInsetsListener { vista, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            vista.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
    }

    /** Letra de títulos y controles: la del sistema en peso medio (antes era monoespaciada). */
    private fun mono(negrita: Boolean) = Typeface.create(if (negrita) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)

    internal fun mezclar(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    // ---------- Texto ----------

    fun texto(ctx: Context, t: String, tam: Float = T_CUERPO, color: Int = p.texto, mono: Boolean = false, negrita: Boolean = false) =
        TextView(ctx).apply {
            text = t
            textSize = tam
            setTextColor(color)
            typeface = if (mono) mono(negrita) else Typeface.create(Typeface.SANS_SERIF, if (negrita) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(0f, 1.35f)
        }

    fun nota(ctx: Context, t: String) = texto(ctx, t, T_NOTA, p.tenue)

    fun tituloPantalla(ctx: Context, t: String) = texto(ctx, t, T_TITULO, p.texto, negrita = true).apply {
        isAccessibilityHeading = true
    }

    /** Título de sección. Es un encabezado para los lectores de pantalla (se navega de uno en uno). */
    fun seccion(ctx: Context, t: String) = texto(ctx, t.uppercase(), T_SECCION, p.acento, mono = true, negrita = true).apply {
        isAccessibilityHeading = true
        letterSpacing = 0.08f
        contentDescription = t
        setPadding(dp(ctx, 4), 0, 0, 0)
    }

    /** Pastilla de estado: símbolo + palabra sobre un fondo teñido con su color. */
    fun chip(ctx: Context, t: String, color: Int) = texto(ctx, t, T_NOTA, color, mono = true, negrita = true).apply {
        setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        background = GradientDrawable().apply {
            setColor(mezclar(p.superficie, color, 0.16f))
            cornerRadius = dp(ctx, 100).toFloat()
        }
    }

    /** Ícono dentro de un círculo teñido con el acento (decorativo). */
    fun icono(ctx: Context, recurso: Int, color: Int = p.acento) = android.widget.ImageView(ctx).apply {
        setImageResource(recurso)
        imageTintList = ColorStateList.valueOf(color)
        val r = dp(ctx, 9)
        setPadding(r, r, r, r)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(mezclar(p.superficie, color, 0.18f)) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---------- Contenedores ----------

    fun columna(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun LinearLayout.agregar(v: View, arriba: Int = 0): View {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, arriba)
        })
        return v
    }

    fun tarjeta(ctx: Context) = columna(ctx).apply {
        setPadding(dp(ctx, PAD_TARJETA), dp(ctx, PAD_TARJETA), dp(ctx, PAD_TARJETA), dp(ctx, PAD_TARJETA))
        background = GradientDrawable().apply {
            setColor(p.superficie)
            cornerRadius = dp(ctx, RADIO_TARJETA).toFloat()
        }
    }

    // ---------- Fondos con foco visible ----------

    fun fondo(ctx: Context, relleno: Int, borde: Int, radioDp: Int = RADIO_CONTROL, grosorDp: Int = 1): StateListDrawable {
        fun forma(r: Int, b: Int, w: Int) = GradientDrawable().apply {
            setColor(r)
            cornerRadius = dp(ctx, radioDp).toFloat()
            setStroke(dp(ctx, w), b)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), forma(relleno, p.foco, 3))
            addState(intArrayOf(android.R.attr.state_pressed), forma(mezclar(relleno, p.acento, 0.25f), borde, grosorDp))
            addState(intArrayOf(), forma(relleno, borde, grosorDp))
        }
    }

    // ---------- Botones (el nativo Button: rol y foco ya vienen del sistema) ----------

    enum class Estilo { PRIMARIO, SECUNDARIO, PELIGRO }

    fun boton(ctx: Context, t: String, estilo: Estilo = Estilo.SECUNDARIO, descripcion: String? = null, accion: () -> Unit) =
        Button(ctx).apply {
            text = t
            isAllCaps = false
            textSize = 15f
            typeface = mono(true)
            letterSpacing = 0.01f
            minHeight = dp(ctx, MIN_TOQUE)
            minimumHeight = dp(ctx, MIN_TOQUE)
            minWidth = dp(ctx, MIN_TOQUE)
            minimumWidth = dp(ctx, MIN_TOQUE)
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
            stateListAnimator = null
            when (estilo) {
                Estilo.PRIMARIO -> { setTextColor(p.sobreAcento); background = fondo(ctx, p.acento, p.acento) }
                Estilo.SECUNDARIO -> { setTextColor(p.acento); background = fondo(ctx, mezclar(p.superficie, p.acento, 0.10f), mezclar(p.superficie, p.acento, 0.10f)) }
                Estilo.PELIGRO -> { setTextColor(p.peligro); background = fondo(ctx, p.superficie, p.peligro) }
            }
            if (descripcion != null) contentDescription = descripcion
            setOnClickListener { accion() }
        }

    /** Fila navegable: título, detalle y una flecha decorativa. Toda la fila es el objetivo. */
    fun filaNav(ctx: Context, titulo: String, detalle: String, icono: Int = 0, accion: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, 64)
        setPadding(dp(ctx, PAD_TARJETA), dp(ctx, 12), dp(ctx, PAD_TARJETA), dp(ctx, 12))
        background = fondo(ctx, p.superficie, p.superficie, RADIO_TARJETA)
        isFocusable = true
        isClickable = true
        if (icono != 0) addView(icono(ctx, icono), LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42)).apply { marginEnd = dp(ctx, 14) })
        val col = columna(ctx)
        col.addView(texto(ctx, titulo, T_CUERPO, p.texto, mono = true, negrita = true))
        col.addView(nota(ctx, detalle))
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(texto(ctx, "›", 24f, p.tenue, mono = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        setOnClickListener { accion() }
    }

    /**
     * Interruptor con el nombre dentro del propio control (una sola zona de toque, sin hueco entre texto y palanca).
     * El texto de ayuda describe el estado actual y cambia con él: «Settings describe the ON state».
     */
    fun interruptor(ctx: Context, titulo: String, activo: Boolean, detalle: (Boolean) -> String, cambio: (Boolean) -> Unit): LinearLayout {
        val caja = tarjeta(ctx)
        val ayuda = nota(ctx, detalle(activo))
        val sw = Switch(ctx).apply {
            text = titulo
            textSize = T_CUERPO
            setTextColor(p.texto)
            typeface = mono(true)
            minHeight = dp(ctx, MIN_TOQUE)
            minimumHeight = dp(ctx, MIN_TOQUE)
            isChecked = activo
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.acento, p.tenue),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(mezclar(p.superficie, p.acento, 0.45f), p.borde),
            )
            setOnCheckedChangeListener { _, v ->
                ayuda.text = detalle(v)
                cambio(v)
            }
        }
        caja.agregar(sw)
        caja.agregar(ayuda, ESP_DENTRO)
        return caja
    }

    /** Opción de un grupo de selección única (modo de lista): botón de radio nativo + frase explicativa. */
    fun radio(ctx: Context, titulo: String, frase: String, marcado: Boolean, alElegir: () -> Unit): LinearLayout {
        val caja = tarjeta(ctx).apply {
            isFocusable = true
            isClickable = true
            background = fondo(ctx, p.superficie, if (marcado) p.acento else p.borde, RADIO_TARJETA, if (marcado) 2 else 1)
        }
        val rb = android.widget.RadioButton(ctx).apply {
            text = titulo
            textSize = T_CUERPO
            setTextColor(p.texto)
            typeface = mono(true)
            minHeight = dp(ctx, MIN_TOQUE)
            isChecked = marcado
            buttonTintList = ColorStateList.valueOf(p.acento)
            isClickable = false
            isFocusable = false
        }
        caja.agregar(rb)
        caja.agregar(nota(ctx, frase), 0)
        caja.contentDescription = "$titulo. $frase" + if (marcado) ". Seleccionado" else ""
        caja.setOnClickListener { alElegir() }
        return caja
    }

    /** Estado con símbolo y palabra, nunca solo color. */
    fun colorDe(n: Nivel) = when (n) { Nivel.LISTO -> p.ok; Nivel.FALTA -> p.peligro; Nivel.AVISO -> p.aviso }

    fun aviso(ctx: Context, titulo: String?, cuerpo: String, nivel: Nivel = Nivel.AVISO): LinearLayout {
        val c = colorDe(nivel)
        return columna(ctx).apply {
            setPadding(dp(ctx, PAD_TARJETA), dp(ctx, PAD_TARJETA), dp(ctx, PAD_TARJETA), dp(ctx, PAD_TARJETA))
            background = fondo(ctx, p.superficie, c, RADIO_TARJETA, 2)
            if (titulo != null) agregar(texto(ctx, "${Semaforo.simbolo(nivel)} $titulo", T_CUERPO, c, mono = true, negrita = true))
            agregar(texto(ctx, cuerpo, T_CUERPO, p.texto), if (titulo != null) ESP_DENTRO else 0)
        }
    }

    // ---------- Campos de texto con etiqueta visible ----------

    class Campo(val vista: LinearLayout, val edit: EditText, private val error: TextView) {
        val texto: String get() = edit.text.toString()

        /** Muestra el error junto al campo, lleva el foco y lo anuncia. null lo limpia. */
        fun fallo(mensaje: String?): Boolean {
            if (mensaje == null) { error.visibility = View.GONE; error.text = ""; return false }
            error.text = "${Textos.SIMBOLO_FALTA} $mensaje"
            error.visibility = View.VISIBLE
            edit.requestFocus()
            error.announceForAccessibility(mensaje)
            return true
        }
    }

    /** La etiqueta siempre visible (el ejemplo va en el hint, nunca en lugar de la etiqueta). Texto a 16sp. */
    fun campo(
        ctx: Context, etiqueta: String, ejemplo: String, valor: String = "", ayuda: String? = null,
        tipo: Int = InputType.TYPE_CLASS_TEXT, lineas: Int = 1, secreto: Boolean = false,
    ): Campo {
        val col = columna(ctx)
        val et = texto(ctx, etiqueta, T_NOTA, p.texto, mono = true, negrita = true)
        val ed = EditText(ctx).apply {
            id = View.generateViewId()
            setText(valor)
            hint = ejemplo
            setHintTextColor(p.tenue)
            setTextColor(p.texto)
            textSize = T_CUERPO
            // setSingleLine cambia la transformación del texto: va antes de fijar inputType (si no, anula el modo contraseña).
            if (lineas > 1) { minLines = lineas; gravity = Gravity.TOP or Gravity.START } else setSingleLine(true)
            inputType = tipo or (if (lineas > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            minHeight = dp(ctx, MIN_TOQUE)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            background = fondo(ctx, p.fondo, p.borde)
            if (secreto) {
                // La clave se puede pegar (no se bloquea el pegado), pero ni se sugiere, ni se autocompleta, ni se muestra.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                this.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        et.labelFor = ed.id
        col.agregar(et)
        if (ayuda != null) col.agregar(nota(ctx, ayuda), 2)
        col.agregar(ed, ESP_DENTRO / 2)
        val err = texto(ctx, "", T_NOTA, p.peligro, negrita = true).apply { visibility = View.GONE }
        col.agregar(err, 4)
        return Campo(col, ed, err)
    }

    /** Zona de estado con anuncio cortés: existe vacía desde el principio y solo cambia su texto. */
    fun zonaEstado(ctx: Context) = texto(ctx, "", T_NOTA, p.texto, negrita = true).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(dp(ctx, MARGEN), dp(ctx, 4), dp(ctx, MARGEN), dp(ctx, 4))
        minimumHeight = 0
    }

    // ---------- Diálogos ----------

    private fun estilarDialogo(ctx: Context, d: AlertDialog, titulo: String) {
        d.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(p.superficie)
            cornerRadius = dp(ctx, RADIO_TARJETA).toFloat()
        })
        d.window?.setTitle(titulo)
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(p.acento); isAllCaps = false; minHeight = dp(ctx, MIN_TOQUE) }
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(p.texto); isAllCaps = false; minHeight = dp(ctx, MIN_TOQUE) }
    }

    /**
     * Diálogo con contenido propio. `aceptar` devuelve true para cerrar; si devuelve false (error de validación) se queda abierto.
     * El foco entra al diálogo y vuelve al control que lo abrió (lo hace el sistema).
     */
    fun dialogo(ctx: Context, titulo: String, contenido: View, positivo: String?, aceptar: () -> Boolean = { true }, negativo: String = Textos.CANCELAR) {
        val caja = columna(ctx).apply { setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 4)) }
        caja.agregar(tituloDialogo(ctx, titulo))
        caja.agregar(contenido, ESP_DENTRO * 2)
        val sv = ScrollView(ctx).apply { addView(caja); isScrollbarFadingEnabled = false }
        val b = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert).setView(sv).setNegativeButton(negativo, null)
        if (positivo != null) b.setPositiveButton(positivo, null)
        val d = b.create()
        d.show()
        estilarDialogo(ctx, d, titulo)
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { if (aceptar()) d.dismiss() }
    }

    private fun tituloDialogo(ctx: Context, t: String) = texto(ctx, t, 18f, p.acento, mono = true, negrita = true).apply { isAccessibilityHeading = true }

    /** Confirmación: título con acción y objeto, cuerpo con lo que se pierde, botones verbo + objeto (better-writing). */
    fun confirmar(ctx: Context, titulo: String, cuerpo: String, verbo: String, accion: () -> Unit) {
        dialogo(ctx, titulo, texto(ctx, cuerpo, T_CUERPO, p.texto), verbo, { accion(); true })
    }

    fun mensaje(ctx: Context, titulo: String, cuerpo: String) {
        dialogo(ctx, titulo, texto(ctx, cuerpo, T_CUERPO, p.texto).apply { setTextIsSelectable(true) }, null, negativo = Textos.CERRAR)
    }

    /** Selección única; devuelve el índice elegido. */
    fun elegir(ctx: Context, titulo: String, opciones: List<String>, actual: Int, elegido: (Int) -> Unit) {
        val d = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(titulo)
            .setSingleChoiceItems(opciones.toTypedArray(), actual) { dl, i -> elegido(i); dl.dismiss() }
            .setNegativeButton(Textos.CANCELAR, null)
            .create()
        d.show()
        estilarDialogo(ctx, d, titulo)
    }
}
