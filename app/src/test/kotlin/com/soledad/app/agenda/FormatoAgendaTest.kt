package com.soledad.app.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FormatoAgendaTest {
    private val zona = TimeZone.getTimeZone("America/Argentina/Jujuy")
    private val H = 3_600_000L

    /** Martes 17/11/2026 a las 14:00 en Argentina. */
    private fun en(dia: Int, hora: Int, min: Int = 0): Long = Calendar.getInstance(zona).apply {
        clear(); set(2026, Calendar.NOVEMBER, dia, hora, min)
    }.timeInMillis

    private val ahora = en(17, 14)

    @Test fun diasRelativos() {
        assertEquals("hoy", FormatoAgenda.dia(en(17, 23, 59), ahora, zona))
        assertEquals("mañana", FormatoAgenda.dia(en(18, 0, 5), ahora, zona))
        assertEquals("ayer", FormatoAgenda.dia(en(16, 9), ahora, zona))
        assertEquals("jueves 19/11", FormatoAgenda.dia(en(19, 9), ahora, zona))
        assertEquals("08:05", FormatoAgenda.hora(en(19, 8, 5), zona))
    }

    @Test fun eventosOrdenadosSinLosTerminados() {
        val ev = listOf(
            Evento("Seguridad Informática", en(18, 18), en(18, 20), false),
            Evento("Ya pasó", en(17, 8), en(17, 9), false),
            Evento("Clase de inglés", en(17, 13), en(17, 15), false),
            Evento("Feriado", en(20, 0), en(21, 0), true),
        )
        val t = FormatoAgenda.texto(ev, null, ahora, zona)
        assertTrue(t.startsWith("Ahora es hoy 14:00."))
        assertFalse(t.contains("Ya pasó"))
        assertTrue(t.contains("- hoy 13:00 a 15:00 (ahora mismo): Clase de inglés"))
        assertTrue(t.contains("- mañana 18:00 a 20:00: Seguridad Informática"))
        assertTrue(t.contains("- viernes 20/11 (todo el día): Feriado"))
        assertTrue(t.indexOf("Clase de inglés") < t.indexOf("Seguridad") && t.indexOf("Seguridad") < t.indexOf("Feriado"))
        assertFalse(t.contains("Tareas")) // tareas = null: My Brain no disponible
    }

    @Test fun tareasConVencimientoPrioridadYVencidas() {
        val ta = listOf(
            Tarea("Sin fecha", null, 0, false),
            Tarea("Entregar TIF", en(22, 23, 59), 2, false),
            Tarea("Pagar luz", en(15, 10), 1, false),
            Tarea("Sacar la basura", en(18, 21), 0, true),
        )
        val t = FormatoAgenda.texto(emptyList(), ta, ahora, zona)
        assertTrue(t.contains("Eventos de los próximos 7 días: ninguno."))
        assertTrue(t.contains("- Pagar luz (vencida (era para domingo 15/11))"))
        assertTrue(t.contains("- Sacar la basura (para mañana 21:00, se repite)"))
        assertTrue(t.contains("- Entregar TIF (para domingo 22/11 23:59, importante)"))
        assertTrue(t.trimEnd().endsWith("- Sin fecha"))
    }

    @Test fun listasVaciasYTope() {
        assertTrue(FormatoAgenda.texto(emptyList(), emptyList(), ahora, zona).contains("Tareas pendientes: ninguna."))
        val muchas = (1..40).map { Evento("E$it", ahora + it * H, ahora + it * H + H, false) }
        val t = FormatoAgenda.texto(muchas, null, ahora, zona)
        assertEquals(FormatoAgenda.MAX_EVENTOS, t.lines().count { it.startsWith("- ") })
        val largo = FormatoAgenda.texto(listOf(Evento("x".repeat(300) + "\n\n", ahora + H, ahora + 2 * H, false)), null, ahora, zona)
        assertTrue(largo.contains("…") && !largo.contains("x".repeat(81)))
    }
}
