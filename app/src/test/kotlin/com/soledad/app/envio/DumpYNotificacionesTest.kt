package com.soledad.app.envio

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class DumpYNotificacionesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val desde = 1_700_000_000_000L

    @Test fun parseaNodosAnidadosYEscapes() {
        val a = DumpUi.parsear(volcado(caja(hoja("x:id/t", "a &amp; b <c>".replace("&amp;", "&"), b = "[1,2,3,4]"))))!!
        assertEquals(2, a.nodos.size)
        assertEquals("a & b <c>", a.nodos[1].texto)
        assertEquals(0, a.nodos[1].padre)
        assertNull(DumpUi.parsear("ERROR: could not get idle state"))
    }

    @Test fun botonEnviarPorIdOPorDescripcionPeroNoNotaDeVoz() {
        val voz = hoja("com.whatsapp:id/voice_note_btn", desc = "Mensaje de voz", b = "[900,2000,1060,2150]")
        assertNull(DumpUi.botonEnviar(DumpUi.parsear(volcado(caja(voz)))!!))
        val env = hoja("com.whatsapp:id/send", desc = "Enviar", b = "[900,2000,1060,2150]", clicable = true)
        val b = DumpUi.botonEnviar(DumpUi.parsear(volcado(caja(voz + env)))!!)!!
        assertEquals(980, b.centroX); assertEquals(2075, b.centroY)
    }

    @Test fun textoExactoConEstado() {
        val a = DumpUi.parsear(volcado(caja(fila("Hola, ¿cómo estás?", "14:32", "Entregado"))))!!
        val h = DumpUi.buscarTexto(a, "Hola, ¿cómo   estás?")!!
        assertEquals(EstadoMensaje.ENTREGADO, h.estado)
        assertEquals("14:32", h.hora)
    }

    @Test fun textoParecidoONoPresenteNoCoincide() {
        val a = DumpUi.parsear(volcado(caja(fila("Hola, ¿cómo estás? Bien", "14:32", "Enviado"))))!!
        assertNull(DumpUi.buscarTexto(a, "Hola, ¿cómo estás?"))
        assertNull(DumpUi.buscarTexto(a, "otra cosa"))
    }

    @Test fun burbujaAjenaSinEstadoNoHeredaElDeOtra() {
        val ajena = fila("Hola", "14:30", null, arriba = 1200)
        val propiaAntes = fila("otra", "14:00", "Leído", arriba = 800)
        val h = DumpUi.buscarTexto(DumpUi.parsear(volcado(caja(propiaAntes + ajena)))!!, "Hola")!!
        assertNull(h.estado)
    }

    @Test fun eligeLaBurbujaMasBajaSiElTextoSeRepite() {
        val a = DumpUi.parsear(volcado(caja(fila("Menú", "10:00", "Leído", 500) + fila("Menú", "14:32", "Pendiente", 1500))))!!
        assertEquals(EstadoMensaje.PENDIENTE, DumpUi.buscarTexto(a, "Menú")!!.estado)
    }

    @Test fun estadosEnIngles() {
        for ((d, e) in listOf("Sent" to EstadoMensaje.ENVIADO, "Delivered" to EstadoMensaje.ENTREGADO, "Read" to EstadoMensaje.LEIDO, "Pending" to EstadoMensaje.PENDIENTE)) {
            val a = DumpUi.parsear(volcado(caja(fila("x y z", "1:00", d))))!!
            assertEquals(e, DumpUi.buscarTexto(a, "x y z")!!.estado)
        }
    }

    @Test fun imagenConYSinPie() {
        val img = caja(
            hoja("com.whatsapp:id/image", desc = "Imagen", b = "[100,900,900,1500]") +
                hoja("com.whatsapp:id/date", "14:33", b = "[800,1500,900,1530]") +
                hoja("com.whatsapp:id/status", desc = "Enviado", b = "[900,1500,940,1530]"),
        )
        val a = DumpUi.parsear(volcado(caja(img)))!!
        assertEquals(EstadoMensaje.ENVIADO, DumpUi.buscarImagen(a, null)!!.estado)
        assertNull("el pie no está en pantalla", DumpUi.buscarImagen(a, "Mi pie")!!.estado)
        val ajena = DumpUi.parsear(volcado(caja(caja(hoja("com.whatsapp:id/image", desc = "Foto") + hoja("com.whatsapp:id/date", "14:33")))))!!
        assertNull(DumpUi.buscarImagen(ajena, null)!!.estado)
    }

    @Test fun horaCompatible() {
        val h = hhmm(desde, utc)
        assertTrue(DumpUi.horaCompatible(h, desde, utc))
        assertFalse("mensaje de hace una hora", DumpUi.horaCompatible(hhmm(desde - 3_600_000L, utc), desde, utc))
        assertTrue(DumpUi.horaCompatible(null, desde, utc))
        assertTrue(DumpUi.horaCompatible("2:32 p. m.", 1_700_000_000_000L - 1_700_000_000_000L % 86_400_000L + 14 * 3_600_000L + 32 * 60_000L, utc))
    }

    // ---- notificaciones ----

    private fun estado(t: LongArray) = EstadoNotificaciones(ahora = { t[0] }, maxChats = 3, maxRepintados = 3, vigenciaMs = 1000)

    @Test fun ecoDelPropioTextoCuenta() {
        val t = longArrayOf(10_000)
        val e = estado(t)
        e.registrar("c", null, listOf(TextoNotif("Hola Ana", "Tú")))
        assertNotNull(e.eco("c", "Hola Ana", 9_000))
        assertNotNull("sin remitente = propio", e.also { it.registrar("d", null, listOf(TextoNotif("Hola Ana", null))) }.eco("d", "Hola  Ana", 9_000))
    }

    @Test fun ecoDeOtroRemitenteONoPosteriorNoCuenta() {
        val t = longArrayOf(10_000)
        val e = estado(t)
        e.registrar("c", null, listOf(TextoNotif("Hola Ana", "Ana")))
        assertNull("lo dijo otra persona", e.eco("c", "Hola Ana", 9_000))
        e.registrar("c", null, listOf(TextoNotif("Hola Ana", "You")))
        assertNull("repintado anterior al envío", e.eco("c", "Hola Ana", 10_001))
        assertNull("texto distinto", e.eco("c", "Hola Anaa", 9_000))
        assertNull("chat distinto", e.eco("z", "Hola Ana", 9_000))
    }

    @Test fun ecoConTextoRecortadoPorWhatsApp() {
        val largo = "Este es un mensaje bastante largo que WhatsApp recorta en la notificación"
        val e = EstadoNotificaciones()
        e.registrar("c", null, listOf(TextoNotif(largo.take(40) + "…", "Tú")))
        assertNotNull(e.eco("c", largo, 0))
        e.registrar("d", null, listOf(TextoNotif("Hola…", "Tú")))
        assertNull("prefijo corto no basta", e.eco("d", "Hola mundo", 0))
    }

    @Test fun memoriaAcotadaYVigencia() {
        val t = longArrayOf(0)
        val e = estado(t)
        val h = HiloChat(null, null, 0)
        listOf("a", "b", "c", "d").forEach { e.registrar(it, h, emptyList()) }
        assertEquals(3, e.cuantosChats())
        assertNull(e.hilo("a"))
        repeat(10) { e.registrar("d", null, listOf(TextoNotif("x", "Tú"))) }
        assertEquals(3, e.repintadosDe("d"))
        t[0] = 5_000
        assertNull("hilo caducado", e.hilo("d"))
    }

    @Test fun lineaDeResumen() {
        assertTrue(TextoNotif.deLinea("Tú: hola", "Ana").propio)
        assertEquals("hola", TextoNotif.deLinea("Tú: hola", "Ana").texto)
        assertFalse(TextoNotif.deLinea("Ana: hola", "Ana").propio)
        assertFalse(TextoNotif.deLinea("hola sin autor", "Ana").propio)
    }

    @Test fun rutasYJid() {
        assertTrue(Rutas.valida("/sdcard/Pictures/a b.jpg"))
        assertFalse(Rutas.valida("/sdcard/../data/x.jpg"))
        assertFalse(Rutas.valida("sdcard/a.jpg"))
        assertFalse(Rutas.valida("/sdcard/a'; rm -rf /.jpg"))
        assertFalse(Rutas.valida("/sdcard//a.jpg"))
        assertTrue(Rutas.hijoDirecto("/sdcard/P", "/sdcard/P/a.jpg"))
        assertFalse(Rutas.hijoDirecto("/sdcard/P", "/sdcard/Pp/a.jpg"))
        assertFalse(Rutas.hijoDirecto("/sdcard/P", "/sdcard/P/sub/a.jpg"))
        assertEquals("5491155550000", Jid.numero("5491155550000@s.whatsapp.net"))
        assertNull(Jid.numero("120363000000@g.us"))
        assertNull(Jid.numero("Ana"))
        assertEquals("%C2%BFC%C3%B3mo%20est%C3%A1s%3F", Texto.urlCodificar("¿Cómo estás?"))
    }
}
