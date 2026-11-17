package com.soledad.app.ui

import android.app.Activity
import android.view.View

/** Lo que una pantalla puede pedirle a la actividad que la aloja. */
interface Anfitrion {
    val actividad: Activity
    fun abrir(p: Pantalla)
    /** Vuelve a construir la pantalla actual conservando el desplazamiento. */
    fun refrescar()
    /** Mensaje en la zona de estado (anuncio cortés para lectores de pantalla). */
    fun avisar(texto: String, error: Boolean = false)
    fun pedirPermisoAvisos()
    /** Pide leer la agenda. `alResponder(true)` si quedó concedido. */
    fun pedirPermisoContactos(alResponder: (Boolean) -> Unit)
    /** Corre `trabajo` fuera del hilo principal y entrega el resultado en el principal (descartado si la actividad ya se cerró). */
    fun <T> enSegundoPlano(trabajo: () -> T, alTerminar: (Result<T>) -> Unit)
}

abstract class Pantalla(protected val h: Anfitrion) {
    abstract val titulo: String
    /** Construye el contenido (una columna). Se vuelve a llamar en cada refresco. */
    abstract fun construir(): View
}
