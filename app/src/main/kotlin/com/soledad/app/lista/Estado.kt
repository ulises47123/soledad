package com.soledad.app.lista

/** Estado persistido: chat→nombre (acotado, sin texto) y marcas de tiempo de respuestas verificadas. */
class EstadoLista(private val almacen: Almacen, private val maxChats: Int = 200) {
    private class Chat(val nombre: String, val vistoMs: Long)

    private val chats = LinkedHashMap<String, Chat>()
    private val respuestas = HashMap<String, MutableList<Long>>()

    init {
        try {
            val r = Json.leer(almacen.leer() ?: "") as? Map<*, *>
            (r?.get("chats") as? Map<*, *>)?.forEach { (k, v) ->
                val m = v as? Map<*, *> ?: return@forEach
                chats[k.toString()] = Chat(m["n"].toString(), (m["t"] as? Number)?.toLong() ?: 0L)
            }
            (r?.get("resp") as? Map<*, *>)?.forEach { (k, v) ->
                respuestas[k.toString()] = (v as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.toMutableList() ?: mutableListOf()
            }
        } catch (_: Exception) {
            // archivo vacío o corrupto: se empieza limpio
        }
    }

    @Synchronized fun vio(id: String, nombre: String, ahora: Long) {
        chats.remove(id)
        chats[id] = Chat(nombre, ahora)
        while (chats.size > maxChats) chats.remove(chats.keys.first())
        guardar()
    }

    @Synchronized fun recientes(max: Int): List<Contacto> =
        chats.entries.sortedByDescending { it.value.vistoMs }.take(max).map { Contacto(it.key, it.value.nombre) }

    @Synchronized fun registrar(id: String, ahora: Long) {
        respuestas.getOrPut(id) { mutableListOf() }.add(ahora)
        respuestas.values.forEach { it.removeAll { t -> t <= ahora - HORA_MS } }
        respuestas.values.removeAll { it.isEmpty() }
        guardar()
    }

    @Synchronized fun enUltimaHora(id: String, ahora: Long): Int =
        respuestas[id]?.count { it > ahora - HORA_MS && it <= ahora } ?: 0

    private fun guardar() {
        val c = chats.mapValues { mapOf("n" to it.value.nombre, "t" to it.value.vistoMs) }
        almacen.escribir(Json.escribir(mapOf("chats" to c, "resp" to respuestas)))
    }

    companion object { const val HORA_MS = 3_600_000L }
}
