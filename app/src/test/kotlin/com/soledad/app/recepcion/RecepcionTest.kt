package com.soledad.app.recepcion

import com.soledad.app.nucleo.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Almacén en memoria: mismo contrato que SQLite, y "persiste" mientras se reutilice la misma instancia. */
class AlmacenMemoria : AlmacenCola {
    val filas = LinkedHashMap<String, FilaCola>()
    override fun insertarSiNuevo(f: FilaCola) = if (filas.containsKey(f.id)) false else { filas[f.id] = f; true }
    override fun obtener(id: String) = filas[id]
    override fun actualizar(f: FilaCola) { filas[f.id] = f }
    override fun listar(estados: Set<EstadoCola>) = filas.values.filter { it.estado in estados }
    override fun purgar(actualizadoAntesDe: Long, estados: Set<EstadoCola>): Int {
        val borrar = filas.values.filter { it.estado in estados && it.actualizadoMs < actualizadoAntesDe }.map { it.id }
        borrar.forEach { filas.remove(it) }
        return borrar.size
    }
}

fun msg(id: String, chat: String = "c1", texto: String = "hola") = Mensaje(id, chat, "Ana", texto, false, 1L, "com.whatsapp")

class ColaTest {
    private var t = 1_000L
    private val almacen = AlmacenMemoria()
    private fun cola(a: AlmacenCola = almacen) = ColaPersistente(a, reloj = { t }, leaseMs = 120_000, maxIntentos = 3)

    @Test fun dedupe() {
        val c = cola()
        assertTrue(c.encolar(msg("a")))
        assertFalse(c.encolar(msg("a")))
        assertEquals(1, c.pendientes())
    }

    @Test fun sobreviveReinicio() {
        cola().encolar(msg("a"))
        val nueva = cola() // nueva instancia sobre el mismo almacenamiento
        assertEquals(1, nueva.pendientes())
        assertEquals("a", nueva.tomar()?.id)
        assertFalse(nueva.encolar(msg("a")))
    }

    @Test fun leaseVencidoReasigna() {
        val c = cola()
        c.encolar(msg("a"))
        assertEquals("a", c.tomar()?.id)
        assertNull(c.tomar())            // EN_PROCESO con lease vigente
        t += 119_000; assertNull(c.tomar())
        t += 2_000
        assertEquals("a", c.tomar()?.id) // lease vencido
        assertEquals(2, almacen.filas["a"]!!.intentos)
    }

    @Test fun leaseVencidoConIntentosAgotadosFalla() {
        val c = cola()
        c.encolar(msg("a"))
        repeat(3) { assertNotNull(c.tomar()); t += 130_000 }
        assertNull(c.tomar())
        assertEquals(EstadoCola.FALLIDO, almacen.filas["a"]!!.estado)
    }

    @Test fun backoffYTopeDeIntentos() {
        val c = cola()
        c.encolar(msg("a"))
        c.tomar(); c.marcar("a", EstadoCola.RECIBIDO, "x")
        assertNull(c.tomar())            // en backoff (5 s)
        t += 5_000
        assertNotNull(c.tomar()); c.marcar("a", EstadoCola.RECIBIDO, "x")
        t += 5_000; assertNull(c.tomar()) // segundo backoff = 10 s
        t += 5_000; assertNotNull(c.tomar()); c.marcar("a", EstadoCola.RECIBIDO, "x")
        assertEquals(EstadoCola.FALLIDO, almacen.filas["a"]!!.estado) // 3 intentos = tope
    }

    @Test fun purgaSoloTerminadosAntiguos() {
        val c = cola()
        c.encolar(msg("a")); c.encolar(msg("b"))
        c.tomar(); c.marcar("a", EstadoCola.VERIFICADO)
        t += 10_000
        assertEquals(1, c.purgar(5_000))
        assertNull(almacen.filas["a"]); assertNotNull(almacen.filas["b"])
    }
}

class ExtractorTest {
    @Test fun idEstableYSoloEntrantes() {
        val ahora = 1_000_000L
        val crudos = listOf(MensajeCrudo(ahora - 10, "hola", "Ana"), MensajeCrudo(ahora - 5, "mi eco", null), MensajeCrudo(ahora - 20 * 60_000, "viejo", "Ana"))
        val e1 = ExtractorWhatsapp.extraer("com.whatsapp", "Ana", null, false, "549@s.whatsapp.net", crudos, ahora)
        val e2 = ExtractorWhatsapp.extraer("com.whatsapp", "Ana", null, false, "549@s.whatsapp.net", crudos, ahora)
        assertEquals(1, e1.mensajes.size)
        assertEquals("549@s.whatsapp.net", e1.chatId)
        assertEquals(e1.mensajes[0].id, e2.mensajes[0].id)
        assertNotEquals(e1.mensajes[0].id, ExtractorWhatsapp.idEstable("549@s.whatsapp.net", ahora - 10, "otro"))
    }
}

