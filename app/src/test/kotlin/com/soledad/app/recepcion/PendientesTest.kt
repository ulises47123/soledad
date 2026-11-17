package com.soledad.app.recepcion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendientesTest {
    private val ahora = 100L * 60 * 60_000 // una hora arbitraria
    private val minutos = 60_000L

    private fun crudo(haceMin: Long, texto: String, quien: String? = "Ana") = MensajeCrudo(ahora - haceMin * minutos, texto, quien)

    private fun vivo(crudos: List<MensajeCrudo>, edad: Long = ExtractorWhatsapp.EDAD_MAXIMA_MS) =
        ExtractorWhatsapp.extraer("com.whatsapp", "Ana", null, false, "x@lid", crudos, ahora, "5491155550123@s.whatsapp.net_1", edad)

    private fun pendientes(crudos: List<MensajeCrudo>, edad: Long) =
        ExtractorWhatsapp.extraerPendientes("com.whatsapp", "Ana", null, false, "x@lid", crudos, ahora, "5491155550123@s.whatsapp.net_1", edad)

    @Test
    fun por_defecto_un_mensaje_de_hace_dos_horas_se_descarta() {
        assertTrue(vivo(listOf(crudo(120, "hola"))).mensajes.isEmpty())
    }

    @Test
    fun con_antiguedad_mayor_el_mismo_mensaje_entra() {
        val e = vivo(listOf(crudo(120, "hola")), edad = 24 * 60 * minutos)
        assertEquals(1, e.mensajes.size)
        assertEquals("hola", e.mensajes[0].texto)
    }

    @Test
    fun sin_limite_entra_aunque_sea_muy_viejo() {
        assertEquals(1, vivo(listOf(crudo(60 * 24 * 30, "hola")), edad = Long.MAX_VALUE).mensajes.size)
    }

    @Test
    fun la_antigüedad_elegida_corta_lo_mas_viejo() {
        val crudos = listOf(crudo(300, "viejo"), crudo(30, "reciente"))
        val e = vivo(crudos, edad = 60 * minutos)
        assertEquals(listOf("reciente"), e.mensajes.map { it.texto })
    }

    @Test
    fun varios_pendientes_del_mismo_chat_se_juntan_en_uno() {
        val e = pendientes(listOf(crudo(50, "primero"), crudo(40, "segundo"), crudo(30, "tercero")), edad = Long.MAX_VALUE)
        assertEquals(1, e.mensajes.size)
        assertEquals("primero\nsegundo\ntercero", e.mensajes[0].texto)
    }

    @Test
    fun el_id_del_pendiente_es_el_del_ultimo_mensaje_asi_la_cola_lo_reconoce_como_duplicado() {
        val crudos = listOf(crudo(50, "primero"), crudo(30, "ultimo"))
        val unico = pendientes(crudos, edad = Long.MAX_VALUE).mensajes.single()
        val enVivo = vivo(crudos, edad = Long.MAX_VALUE).mensajes.last()
        assertEquals(enVivo.id, unico.id)
        assertEquals(enVivo.recibidoMs, unico.recibidoMs)
    }

    @Test
    fun los_propios_nunca_se_incluyen_y_si_no_queda_nada_no_hay_mensaje() {
        val e = pendientes(listOf(crudo(10, "yo escribi esto", quien = null), crudo(5, "eco", quien = "")), edad = Long.MAX_VALUE)
        assertTrue(e.mensajes.isEmpty())
    }

    @Test
    fun un_solo_pendiente_queda_igual() {
        val e = pendientes(listOf(crudo(20, "solo uno")), edad = Long.MAX_VALUE)
        assertEquals("solo uno", e.mensajes.single().texto)
    }

    @Test
    fun el_orden_se_respeta_aunque_lleguen_desordenados() {
        val e = pendientes(listOf(crudo(10, "b"), crudo(50, "a")), edad = Long.MAX_VALUE)
        assertEquals("a\nb", e.mensajes.single().texto)
    }
}
