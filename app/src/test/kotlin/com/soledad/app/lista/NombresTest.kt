package com.soledad.app.lista

import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Veredicto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class NombresTest {
    @Test fun normalizaAcentosEmojisYEspacios() {
        assertEquals("mama", Nombres.normalizar("  Mamá ❤️ "))
        assertEquals("juan perez", Nombres.normalizar("Juan   Pérez"))
        assertEquals("juan perez", Nombres.normalizar("‎Juan Pérez 🙂‏"))
        assertEquals("ana 2", Nombres.normalizar("Ana (2)"))
        assertEquals("", Nombres.normalizar("🔥🔥"))
    }

    @Test fun mismoContacto() {
        assertTrue(Nombres.mismo("Mamá ❤️", "mama"))
        assertTrue(Nombres.mismo("Juan  Pérez", "juan perez 🙂"))
        assertFalse(Nombres.mismo("Juan Pérez", "Juana Pérez"))
        assertFalse(Nombres.mismo("Ana", "Ana María"))
        // nombres hechos solo de emojis: se exige el mismo texto, no «vacío == vacío»
        assertTrue(Nombres.mismo("🔥🔥", " 🔥🔥 "))
        assertFalse(Nombres.mismo("🔥🔥", "🌙"))
        assertFalse(Nombres.mismo("", ""))
    }

    @Test fun agruparQuitaRepetidosYVaciosYOrdena() {
        val filas = listOf(
            ContactoAgenda("Beto", "+54 9 388 111-2222"), ContactoAgenda("Ana", "111"), ContactoAgenda("beto", "333"),
            ContactoAgenda("  ", "999"), ContactoAgenda("Álvaro", "555"),
        )
        assertEquals(listOf("Álvaro", "Ana", "Beto"), Nombres.agrupar(filas).map { it.nombre })
    }

    @Test fun filtrarPorNombreSinAcentosOPorNumero() {
        val l = listOf(ContactoAgenda("Martín Gómez", "+54 9 388 462 1234"), ContactoAgenda("Ana", "011 5555 9876"), ContactoAgenda("Mamá ❤️", ""))
        assertEquals(listOf("Martín Gómez"), Nombres.filtrar(l, "martin").map { it.nombre })
        assertEquals(listOf("Martín Gómez"), Nombres.filtrar(l, "GOM").map { it.nombre })
        assertEquals(listOf("Mamá ❤️"), Nombres.filtrar(l, "mama").map { it.nombre })
        assertEquals(listOf("Ana"), Nombres.filtrar(l, "9876").map { it.nombre })
        assertEquals(3, Nombres.filtrar(l, "  ").size)
        assertTrue(Nombres.filtrar(l, "zzz").isEmpty())
        assertEquals("1234", Nombres.finNumero("+54 9 388 462 1234"))
    }

    @Test fun agregarNombresDesdeLaAgendaSinRepetir() {
        val repo = RepositorioConfig(AlmacenMemoria()).also { it.guardar(ConfigLista()) }
        val lc = ListaContactos(repo, EstadoLista(AlmacenMemoria()))
        assertEquals(2, lc.agregarNombres(listOf("Mamá ❤️", "Juan  Pérez")))
        assertEquals(0, lc.agregarNombres(listOf("mama", "juan pérez", "  ")))
        assertEquals(1, lc.agregarNombres(listOf("Ana", "ana")))
        assertEquals(listOf("Mamá ❤️", "Juan Pérez", "Ana"), lc.listar().map { it.nombre })
    }

    @Test fun laNotificacionCoincideAunqueCambienEmojisYEspacios() {
        val repo = RepositorioConfig(AlmacenMemoria())
        repo.guardar(ConfigLista(interruptorGeneral = true, contactos = listOf(Contacto("Mamá ❤️", "Mamá ❤️"))))
        val v = VerificadorListaImpl(repo, EstadoLista(AlmacenMemoria()), { 0L }, TimeZone.getTimeZone("UTC"))
        fun m(nombre: String) = Mensaje("id$nombre", "chat", nombre, "hola, ¿estás en casa?", false, 0L, "com.whatsapp")
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(m("Mamá")))
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(m("mama ❤")))
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(m("Mamá Rosa")))
    }
}
