package com.soledad.app.lista

import android.content.Context

/** Fachada para la interfaz: edita la lista y los interruptores sin que la UI toque el YAML. */
class ListaContactos(private val repo: RepositorioConfig, private val estado: EstadoLista) {
    fun leerConfig(): ConfigLista = repo.leer()
    fun guardarConfig(c: ConfigLista) = repo.guardar(c)

    fun listar(): List<Contacto> = repo.leer().contactos

    @Synchronized fun agregar(c: Contacto) {
        val cfg = repo.leer()
        repo.guardar(cfg.copy(contactos = cfg.contactos.filter { it.id != c.id } + c))
    }

    /** Agrega varios nombres (elegidos de la agenda). Salta los que ya están (comparando por nombre normalizado). Devuelve cuántos agregó. */
    @Synchronized fun agregarNombres(nombres: List<String>): Int {
        val cfg = repo.leer()
        val nuevos = ArrayList<Contacto>()
        for (n in nombres.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }) {
            if ((cfg.contactos + nuevos).any { Nombres.mismo(it.nombre, n) }) continue
            nuevos += Contacto(n, n)
        }
        if (nuevos.isNotEmpty()) repo.guardar(cfg.copy(contactos = cfg.contactos + nuevos))
        return nuevos.size
    }

    @Synchronized fun quitar(id: String) {
        val cfg = repo.leer()
        repo.guardar(cfg.copy(contactos = cfg.contactos.filter { it.id != id }))
    }

    /** Chats vistos recientemente (más nuevo primero). */
    fun recientes(max: Int = 30): List<Contacto> = estado.recientes(max)

    fun setInterruptor(activo: Boolean) = repo.guardar(repo.leer().copy(interruptorGeneral = activo))
    fun setModo(modo: ModoLista) = repo.guardar(repo.leer().copy(modo = modo))

    companion object {
        fun crear(ctx: Context): ListaContactos = VerificadorListaImpl.crearConjunto(ctx).second
    }
}
