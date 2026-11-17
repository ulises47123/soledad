package com.soledad.app.lista

import java.io.File

/** Abstracción de almacenamiento de texto para poder probar en JVM sin Android. */
interface Almacen {
    fun leer(): String?
    fun escribir(contenido: String)
}

/** Escritura atómica: archivo temporal y rename, así un corte de energía no deja el archivo a medias. */
class AlmacenArchivo(private val archivo: File) : Almacen {
    override fun leer(): String? = if (archivo.exists()) archivo.readText() else null

    @Synchronized
    override fun escribir(contenido: String) {
        archivo.parentFile?.mkdirs()
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.writeText(contenido)
        if (!tmp.renameTo(archivo)) {
            archivo.delete()
            check(tmp.renameTo(archivo)) { "No se pudo reemplazar ${archivo.name}" }
        }
    }
}

class AlmacenMemoria(var contenido: String? = null) : Almacen {
    override fun leer() = contenido
    override fun escribir(contenido: String) { this.contenido = contenido }
}
