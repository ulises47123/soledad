package com.soledad.app.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ConsultaTest {
    private val zona = TimeZone.getTimeZone("America/Argentina/Jujuy")

    private fun en(dia: Int, hora: Int, min: Int = 0, mes: Int = Calendar.NOVEMBER, anio: Int = 2026): Long =
        Calendar.getInstance(zona).apply { clear(); set(anio, mes, dia, hora, min) }.timeInMillis

    /** Miércoles 18/11/2026 a las 10:00. */
    private val ahora = en(18, 10)

    private fun v(t: String) = LectorFecha.ventana(t, ahora, zona)

    @Test fun sabadoPorLaTarde() {
        val w = v("Hola! qué vas a hacer el sábado por la tarde?")!!
        assertEquals(en(21, 13), w.inicioMs)
        assertEquals(en(21, 20), w.finMs)
        assertEquals("sábado 21/11 por la tarde (13:00 a 20:00)", w.etiqueta)
        assertTrue(w.parte)
    }

    @Test fun mananaComoDiaYComoParte() {
        val dia = v("estás libre mañana?")!!
        assertEquals(en(19, 0), dia.inicioMs); assertEquals(en(20, 0), dia.finMs)
        assertEquals("mañana, jueves 19/11", dia.etiqueta)
        assertFalse(dia.parte)

        val parte = v("podés el viernes a la mañana?")!!
        assertEquals(en(20, 6), parte.inicioMs); assertEquals(en(20, 12), parte.finMs)

        val ambas = v("nos vemos mañana a la mañana?")!!
        assertEquals(en(19, 6), ambas.inicioMs); assertEquals(en(19, 12), ambas.finMs)

        val pasado = v("y pasado mañana a la noche?")!!
        assertEquals(en(20, 20), pasado.inicioMs); assertEquals(en(21, 0), pasado.finMs)
    }

    @Test fun hoyYParteSinDia() {
        assertEquals(en(18, 0), v("que haces hoy")!!.inicioMs)
        val noche = v("salimos esta noche?")!!
        assertEquals(en(18, 20), noche.inicioMs)
        val tarde = v("a la tarde estás?")!!
        assertEquals(en(18, 13), tarde.inicioMs)
    }

    @Test fun horas() {
        val a = v("te veo el jueves a las 18")!!
        assertEquals(en(19, 18), a.inicioMs); assertEquals(en(19, 20), a.finMs)
        assertEquals(en(18, 18, 30), v("hoy 18:30 podes?")!!.inicioMs)
        assertEquals(en(19, 17), v("mañana a las 5 te parece?")!!.inicioMs) // 5 → 17:00
        assertEquals(en(19, 9), v("mañana a las 9 de la mañana")!!.inicioMs)
        assertEquals(en(21, 21), v("el sabado a las 9 de la noche")!!.inicioMs)
    }

    @Test fun fechasYFinde() {
        assertEquals(en(25, 0), v("el 25 tenés algo?")!!.inicioMs)
        assertEquals(en(5, 0, 0, Calendar.DECEMBER), v("y el 5 que hacés")!!.inicioMs) // ya pasó este mes
        assertEquals(en(3, 0, 0, Calendar.DECEMBER), v("nos juntamos el 3/12")!!.inicioMs)
        assertEquals(en(10, 0, 0, Calendar.JANUARY, 2027), v("el 10 de enero viajás?")!!.inicioMs)
        val finde = v("que hacés el finde?")!!
        assertEquals(en(21, 0), finde.inicioMs); assertEquals(en(23, 0), finde.finMs)
        assertTrue(finde.etiqueta.startsWith("el fin de semana"))
    }

    @Test fun mismoDiaDeLaSemana() {
        assertEquals(en(18, 0), v("el miércoles podés?")!!.inicioMs)
        assertEquals(en(25, 0), v("el miércoles que viene podés?")!!.inicioMs)
        assertEquals(en(23, 0), v("el lunes")!!.inicioMs)
    }

    @Test fun sinMomentoNoHayVentana() {
        assertNull(v("hola como estas"))
        assertNull(v("buenas tardes"))
        assertNull(v("buenas noches, todo bien?"))
        assertNull(v("te escribo más tarde"))
        assertNull(v("jaja dale"))
    }

    @Test fun choqueYRatosLibres() {
        val w = v("qué hacés el sábado por la tarde?")!!
        val ev = listOf(
            Evento("Ensayo de la banda", en(21, 15), en(21, 17), false),
            Evento("Desayuno", en(21, 9), en(21, 10), false),
            Evento("Otro día", en(22, 15), en(22, 16), false),
            Evento("Cumple de Ana", en(21, 0), en(22, 0), true),
        )
        val ta = listOf(Tarea("Entregar TP", en(21, 23, 59), 2, false), Tarea("Otra", en(25, 10), 0, false))
        val t = Choques.texto(w, ev, ta, ahora, zona)
        assertTrue(t, t.startsWith("TE PREGUNTAN POR: sábado 21/11 por la tarde (13:00 a 20:00)."))
        assertTrue(t, t.contains("En ese horario estás ocupado con:\n- 15:00 a 17:00: Ensayo de la banda"))
        assertTrue(t, t.contains("Te queda libre: 13:00 a 15:00 y 17:00 a 20:00."))
        assertTrue(t, t.contains("Ese día además (todo el día): Cumple de Ana."))
        assertTrue(t, t.contains("El resto de ese día: 09:00 a 10:00: Desayuno."))
        assertTrue(t, t.contains("Tareas que vencen ahí: Entregar TP."))
        assertFalse(t, t.contains("Otro día"))
        assertFalse(t, t.contains("Otra"))
    }

    @Test fun libreYOcupadoTodoElHorario() {
        val w = v("el sábado a la noche salimos?")!!
        val libre = Choques.texto(w, emptyList(), null, ahora, zona)
        assertTrue(libre, libre.contains("En ese horario no tenés nada anotado: estás libre."))
        assertFalse(libre.contains("Tareas"))

        val lleno = Choques.texto(w, listOf(Evento("Turno", en(21, 19), en(22, 1), false)), null, ahora, zona)
        assertTrue(lleno, lleno.contains("- 19:00 a 01:00: Turno"))
        assertTrue(lleno, lleno.contains("No te queda ningún rato libre en ese horario."))
    }

    @Test fun diaEnteroCuentaLibresDe8a22() {
        val w = v("mañana qué hacés?")!!
        val t = Choques.texto(w, listOf(Evento("Clase", en(19, 18), en(19, 22), false)), null, ahora, zona)
        assertTrue(t, t.contains("- 18:00 a 22:00: Clase"))
        assertTrue(t, t.contains("Te queda libre: 08:00 a 18:00."))
    }

    @Test fun huecosIgnoraRatosCortos() {
        val h = Choques.huecos(en(21, 13), en(21, 20), listOf(
            Evento("A", en(21, 13, 10), en(21, 15), false), Evento("B", en(21, 15, 20), en(21, 19, 45), false),
        ))
        assertTrue(h.isEmpty())
        assertNotNull(h)
    }

    @Test fun buscaPorNombreEnElCalendario() {
        fun en27(dia: Int, mes: Int) = en(dia, 0, 0, mes, 2027)
        val ev = listOf(
            Evento("Cumpleaños de Edit", en27(20, Calendar.JUNE), en27(21, Calendar.JUNE), true),
            Evento("Cumpleaños de Ana Lucía", en27(9, Calendar.MARCH), en27(10, Calendar.MARCH), true),
            Evento("Parcial de Redes", en(24, 18), en(24, 20), false),
            Evento("Parcial de Ética", en(26, 18), en(26, 20), false),
        )
        val cumple = Coincidencias.elegir("Cuando es el cumple de ana lucia ?", ev, ahora)
        assertEquals(listOf("Cumpleaños de Ana Lucía"), cumple.map { it.titulo })
        assertEquals(cumple, Coincidencias.elegir("En el calendario cuando es cumpleaños de ana lucia?", ev, ahora))
        val t = Coincidencias.texto(cumple, ahora, zona)
        assertTrue(t, t.contains("- martes 9/3/2027 (todo el día): Cumpleaños de Ana Lucía"))

        assertEquals(listOf("Parcial de Redes"), Coincidencias.elegir("cuando tenes el parcial de redes?", ev, ahora).map { it.titulo })
        // Una palabra genérica sola no alcanza: no se listan todos los cumpleaños ni todos los parciales.
        assertTrue(Coincidencias.elegir("cuando es tu cumple?", ev, ahora).isEmpty())
        assertTrue(Coincidencias.elegir("cuando tenes parcial?", ev, ahora).isEmpty())
        assertTrue(Coincidencias.elegir("hola como estas", ev, ahora).isEmpty())
        assertEquals("", Coincidencias.texto(emptyList(), ahora, zona))
    }

    @Test fun eligeLasNotasQueVienenAlCaso() {
        val notas = listOf(
            NotaCerebro("Inglés técnico", "Unit 3: **present perfect**. Vocabulary: router, switch.", "Cuaderno", 300),
            NotaCerebro("Lista del súper", "pan, leche, yerba", null, 200),
            NotaCerebro("Ética", "Apuntes de la clase 4. El profe habló de ingles una vez.", "Cuaderno", 100),
            NotaCerebro("Ideas", "Armar un router casero con una Raspberry y un switch viejo.", null, 50),
        )
        val r = NotasCerebro.elegir("Tienes cuaderno de inglés?", notas)
        assertEquals("Inglés técnico", r.first().titulo)
        val t = NotasCerebro.texto(r.take(1))
        assertTrue(t, t.startsWith("EN TUS NOTAS DE MI CEREBRO"))
        assertTrue(t, t.contains("- [carpeta Cuaderno] Inglés técnico: Unit 3: present perfect . Vocabulary: router, switch."))
        // Dos palabras en el texto alcanzan; una sola perdida, no.
        assertEquals(listOf("Ideas"), NotasCerebro.elegir("tenes anotado lo del router y el switch?", notas.drop(1)).map { it.titulo })
        assertTrue(NotasCerebro.elegir("que onda la raspberry", notas).isEmpty())
        assertTrue(NotasCerebro.elegir("hola como estas", notas).isEmpty())
        assertEquals("", NotasCerebro.texto(emptyList()))
    }

    @Test fun `de una nota con varios links va solo el que piden`() {
        val mate = "https://ejemplo.test/notebook/aaa_111__x"
        val ingles = "https://ejemplo.test/notebook/bbb-222"
        val notas = listOf(NotaCerebro("cuadernos", "matemática es $mate\n\n\ningles: $ingles\nfísica -> https://ejemplo.test/notebook/ccc", null, 10))
        val pregunta = "Tenés el cuaderno de inglés?"
        val t = NotasCerebro.texto(NotasCerebro.elegir(pregunta, notas), pregunta)
        assertTrue(t, t.contains("  LINK PEDIDO (ingles): $ingles\n"))
        assertFalse(t, t.contains(mate) || t.contains("ccc"))
        assertTrue(t, t.contains("Tiene además links de: matemática, física"))
        // El link va entero aunque tenga guiones bajos.
        val m = NotasCerebro.texto(notas, "pasame el de matematica")
        assertTrue(m, m.contains("LINK PEDIDO (matemática): $mate\n"))
        // Si no se sabe cuál piden, no sale ninguna dirección.
        val n = NotasCerebro.texto(notas, "pasame el link del cuaderno")
        assertFalse(n, n.contains("http"))
        assertTrue(n, n.contains("Tiene links guardados de: matemática, ingles, física"))
    }

    @Test fun `la respuesta solo puede llevar links que esten en las notas`() {
        val ok = "https://ejemplo.test/notebook/bbb-222"
        val permitidos = listOf(ok, "https://otro.test/x")
        assertEquals("Sí, acá va: $ok.", Links.asegurar("Sí, acá va: $ok.", permitidos))
        assertEquals("Acá va: $ok", Links.asegurar("Acá va: https://ejemplo.test/notebook/bbb-2", permitidos))
        assertEquals("Acá va:", Links.asegurar("Acá va: https://inventado.test/zzz", permitidos))
        assertEquals("Te lo paso.", Links.asegurar("Te lo paso https://inventado.test/zzz.", emptyList()))
        assertEquals(listOf(ok), Links.en("mirá ($ok)."))
        // La limpieza de la respuesta no rompe un link con guiones bajos ni lo corta.
        val largo = "https://ejemplo.test/notebook/aaa_111__x"
        assertEquals("Acá lo tenés: $largo", com.soledad.app.rag.IaConRagImpl.limpiar("**Acá lo tenés:** $largo", listOf(largo)))
    }
}
