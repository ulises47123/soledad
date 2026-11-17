package com.soledad.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicaTest {

    // ---------- Validadores ----------

    @Test fun nombreDeContacto() {
        assertNull(Validar.nombreContacto("Mamá", emptyList()))
        assertEquals(Textos.ERR_NOMBRE_VACIO, Validar.nombreContacto("   ", emptyList()))
        assertEquals(Textos.ERR_NOMBRE_REPETIDO, Validar.nombreContacto(" mamá ", listOf("Mamá")))
        assertEquals(Textos.ERR_NOMBRE_LARGO, Validar.nombreContacto("x".repeat(61), emptyList()))
        assertEquals(Textos.ERR_NOMBRE_SALTO, Validar.nombreContacto("a\nb", emptyList()))
    }

    @Test fun claves() {
        assertNull(Validar.claveApi("nvapi-abcdefghijkl"))
        assertEquals(Textos.ERR_API_CLAVE, Validar.claveApi("corta"))
        assertEquals(Textos.ERR_API_CLAVE, Validar.claveApi("con espacios en medio"))
    }

    // ---------- Semáforo ----------

    private fun entrada(
        n: Boolean = true, i: Boolean = true, c: Boolean = true, p: Boolean = true, b: Boolean = true, k: Boolean = true,
        v: Boolean = true, x: Boolean = true,
    ) = EntradaSemaforo(n, i, c, p, b, k, v, x)

    @Test fun semaforoTodoListo() {
        val s = Semaforo.evaluar(entrada())
        assertEquals(6, s.size)
        assertEquals(0, Semaforo.pendientes(s))
        assertEquals(Textos.SEMAFORO_TODO_LISTO, Semaforo.resumen(s))
        assertTrue(s.all { it.accion == null })
    }

    @Test fun soloNotificacionesYClaveBloqueanElEncendido() {
        assertEquals(0, Semaforo.bloqueantes(Semaforo.evaluar(entrada(i = false, b = false, v = false, x = false))))
        assertEquals(1, Semaforo.bloqueantes(Semaforo.evaluar(entrada(n = false))))
        assertEquals(2, Semaforo.bloqueantes(Semaforo.evaluar(entrada(n = false, k = false))))
    }

    @Test fun semaforoAcciones() {
        val s = Semaforo.evaluar(entrada(n = false, c = false, k = false, x = false))
        assertEquals(Accion.ABRIR_NOTIFICACIONES, s.first { it.id == "notif" }.accion)
        assertEquals(Accion.ABRIR_SHIZUKU, s.first { it.id == "shizuku" }.accion)
        assertEquals(Accion.IR_AJUSTES, s.first { it.id == "clave" }.accion)
        assertEquals(Accion.ALARMAS_EXACTAS, s.first { it.id == "alarmas" }.accion)
        assertEquals(Textos.semaforoFaltan(4), Semaforo.resumen(s))
        assertNull(Semaforo.evaluar(entrada(i = false, c = false, p = false)).first { it.id == "shizuku" }.accion)
        assertEquals(Accion.PEDIR_SHIZUKU, Semaforo.evaluar(entrada(p = false)).first { it.id == "shizuku" }.accion)
    }

    @Test fun cadaNivelTieneSimboloYPalabraDistintos() {
        assertEquals(3, Nivel.values().map { Semaforo.simbolo(it) }.toSet().size)
        assertEquals(3, Nivel.values().map { Semaforo.palabra(it) }.toSet().size)
    }

    // ---------- Registro ----------

    @Test fun registroSeParsea() {
        val l = LectorRegistro.parsear("1700000000000 | abc123 | prueba | ok | con barra | extra")!!
        assertEquals(1700000000000L, l.ts)
        assertEquals("abc123", l.mensaje)
        assertEquals("prueba", l.etapa)
        assertEquals("ok | con barra | extra", l.detalle)
        assertNull(LectorRegistro.parsear("basura"))
        assertNull(LectorRegistro.parsear("x | a | b | c"))
    }

    @Test fun etapasConNombreYNivel() {
        assertEquals(Nivel.FALTA, LectorRegistro.etiqueta("error").second)
        assertEquals(Textos.ET_PRUEBA, LectorRegistro.etiqueta("prueba").first)
        assertEquals(Textos.ET_SISTEMA, LectorRegistro.etiqueta("otra").first)
    }

    @Test fun tiempoRelativo() {
        assertEquals("en 34 min", relativo(0, 34 * 60_000L))
        assertEquals("en 2 h 05 min", relativo(0, 125 * 60_000L))
        assertEquals(Textos.EN_UN_MOMENTO, relativo(10, 5))
    }

    // ---------- Contraste de la paleta (better-colors / better-accessibility 1.4.3 y 1.4.11) ----------

    @Test fun paletaCumpleContraste() {
        val p = Paletas.SOLEDAD
        fun texto(nombre: String, fg: Int, bg: Int) = assertTrue("$nombre = ${Contraste.relacion(fg, bg)}", Contraste.relacion(fg, bg) >= 4.5)
        fun grafico(nombre: String, fg: Int, bg: Int) = assertTrue("$nombre = ${Contraste.relacion(fg, bg)}", Contraste.relacion(fg, bg) >= 3.0)
        for (fondo in listOf(p.fondo, p.superficie)) {
            texto("texto/fondo", p.texto, fondo)
            texto("tenue/fondo", p.tenue, fondo)
            texto("acento/fondo", p.acento, fondo)
            texto("ok/fondo", p.ok, fondo)
            texto("aviso/fondo", p.aviso, fondo)
            texto("peligro/fondo", p.peligro, fondo)
            grafico("foco/fondo", p.foco, fondo)
            grafico("borde/fondo", p.borde, fondo)
        }
        texto("sobreAcento/acento", p.sobreAcento, p.acento)
    }
}
