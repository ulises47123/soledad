package com.soledad.app.lista

import com.soledad.app.recepcion.ExtractorWhatsapp
import com.soledad.app.recepcion.MensajeCrudo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelefonoTest {
    // Números inventados (formato argentino: país 54, móvil con 9).
    private val jid = "5491155550123@s.whatsapp.net"

    @Test
    fun agenda_con_distintos_formatos_coincide_con_el_jid() {
        assertTrue(Telefono.mismo("+54 9 11 5555-0123", jid))
        assertTrue(Telefono.mismo("011 5555-0123", jid))
        assertTrue(Telefono.mismo("(11) 5555 0123", jid))
        assertTrue(Telefono.mismo("5491155550123", jid))
        assertTrue(Telefono.mismo(jid, "+549 11 5555 0123"))
    }

    @Test
    fun jid_con_sufijo_de_dispositivo_se_reconoce() {
        assertEquals("5491155550123", Telefono.deJid("5491155550123:12@s.whatsapp.net"))
        assertTrue(Telefono.mismo("1155550123", "5491155550123:12@s.whatsapp.net"))
    }

    @Test
    fun otro_numero_no_coincide() {
        assertFalse(Telefono.mismo("+54 9 11 5555-0124", jid))
        assertFalse(Telefono.mismo("+54 9 11 6666-0123", jid))
    }

    @Test
    fun lid_grupos_y_nombres_nunca_se_toman_por_telefono() {
        assertEquals("", Telefono.deJid("5240000000001@lid"))
        assertEquals("", Telefono.deJid("120363012345678901@g.us"))
        assertFalse(Telefono.mismo("5240000000001@lid", "5240000000001"))
        assertFalse(Telefono.mismo("Ana Pérez", jid))
        assertEquals("", Telefono.digitos("Ana 123"))
    }

    @Test
    fun numeros_demasiado_cortos_no_coinciden() {
        assertFalse(Telefono.mismo("123456", "123456@s.whatsapp.net"))
    }

    // ---------- Reconocimiento del número real en el canal de la notificación ----------

    @Test
    fun el_canal_de_la_notificacion_da_el_jid_real() {
        assertEquals(jid, ExtractorWhatsapp.jidDelCanal("5491155550123@s.whatsapp.net_8"))
        assertEquals("5491155550123:3@s.whatsapp.net", ExtractorWhatsapp.jidDelCanal("5491155550123:3@s.whatsapp.net_2"))
        assertEquals(null, ExtractorWhatsapp.jidDelCanal("messages_channel"))
        assertEquals(null, ExtractorWhatsapp.jidDelCanal("120363012345678901@g.us_1"))
        assertEquals(null, ExtractorWhatsapp.jidDelCanal(null))
    }

    @Test
    fun el_chat_usa_el_jid_del_canal_y_no_el_lid() {
        val crudos = listOf(MensajeCrudo(1_000, "hola", "Ana"))
        val e = ExtractorWhatsapp.extraer(
            "com.whatsapp", "Ana", null, false, "5240000000001@lid", crudos, 1_000, "5491155550123@s.whatsapp.net_8",
        )
        assertEquals(jid, e.chatId)
        assertEquals(jid, e.mensajes.single().chatId)
        // Sin canal reconocible se conserva el comportamiento anterior.
        val sin = ExtractorWhatsapp.extraer("com.whatsapp", "Ana", null, false, "5240000000001@lid", crudos, 1_000, "otro")
        assertEquals("5240000000001@lid", sin.chatId)
    }
}
