package com.soledad.app.lista

import java.text.Normalizer

/**
 * Lo marcado en el selector de la agenda. SOLO agrega: lo que ya está en la lista no se puede desmarcar desde acá
 * (aunque el buscador lo esconda) y nunca se quita nada; quitar es exclusivo del botón «Quitar».
 */
class SeleccionAgenda(private val enLista: List<String>) {
    private val marcados = LinkedHashSet<String>()

    fun yaEnLista(nombre: String) = enLista.any { Nombres.mismo(it, nombre) }
    fun marcado(nombre: String) = yaEnLista(nombre) || nombre in marcados

    /** Cambia la marca. Lo que ya está en la lista no se toca. */
    fun marcar(nombre: String, si: Boolean) {
        if (yaEnLista(nombre)) return
        if (si) marcados += nombre else marcados -= nombre
    }

    fun cantidad() = marcados.size

    /** Nombres a AGREGAR (nunca incluye ni afecta a los que ya estaban). */
    fun aAgregar(): List<String> = marcados.filter { !yaEnLista(it) }
}

/** Un contacto de la agenda del teléfono: solo el nombre visible y, si hay, un número para distinguir homónimos. */
data class ContactoAgenda(val nombre: String, val numero: String = "")

/**
 * Comparación de nombres de contacto entre la agenda, la lista blanca y la notificación de WhatsApp.
 * Lógica pura (se prueba en la JVM).
 */
object Nombres {
    private val MARCAS = Regex("\\p{M}+")
    private val NO_ALFANUM = Regex("[^\\p{L}\\p{N}]+")
    private val ESPACIOS = Regex("\\s+")

    /**
     * Minúsculas, sin acentos, sin emojis ni signos, espacios colapsados.
     * «  Mamá ❤️ » → «mama»; «Juan  Pérez» → «juan perez».
     */
    fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARCAS, "")
            .replace(NO_ALFANUM, " ").trim()

    /** Mismo contacto por nombre. Si alguno queda vacío al normalizar (solo emojis), se exige el texto igual sin espacios de más. */
    fun mismo(a: String, b: String): Boolean {
        val x = normalizar(a)
        val y = normalizar(b)
        if (x.isEmpty() || y.isEmpty()) return a.trim().isNotEmpty() && a.trim().replace(ESPACIOS, " ") == b.trim().replace(ESPACIOS, " ")
        return x == y
    }

    /** Une filas repetidas de la agenda (un contacto con varios números) y ordena por nombre. Descarta nombres vacíos. */
    fun agrupar(filas: List<ContactoAgenda>): List<ContactoAgenda> {
        val porNombre = LinkedHashMap<String, ContactoAgenda>()
        for (f in filas) {
            val n = f.nombre.trim().replace(ESPACIOS, " ")
            if (n.isEmpty()) continue
            val clave = normalizar(n).ifEmpty { n }
            if (clave !in porNombre) porNombre[clave] = ContactoAgenda(n, f.numero.trim())
        }
        return porNombre.values.sortedWith(compareBy({ normalizar(it.nombre).ifEmpty { it.nombre } }, { it.nombre }))
    }

    /** Filtra por lo escrito en el buscador (sin acentos ni mayúsculas; también por dígitos del número). */
    fun filtrar(lista: List<ContactoAgenda>, consulta: String): List<ContactoAgenda> {
        val q = normalizar(consulta)
        if (q.isEmpty()) return lista
        val digitos = consulta.filter { it.isDigit() }
        return lista.filter { c ->
            normalizar(c.nombre).contains(q) || (digitos.length >= 3 && c.numero.filter { it.isDigit() }.contains(digitos))
        }
    }

    /** Los últimos 4 dígitos, para distinguir homónimos sin mostrar el número entero. */
    fun finNumero(numero: String): String = numero.filter { it.isDigit() }.takeLast(4)
}
