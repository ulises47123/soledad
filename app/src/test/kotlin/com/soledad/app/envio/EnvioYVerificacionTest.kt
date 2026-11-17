package com.soledad.app.envio

import com.soledad.app.nucleo.AccionEnvio
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class EnvioYVerificacionTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val chat = "5491155550000@s.whatsapp.net"
    private val botonEnviar = hoja("com.whatsapp:id/send", desc = "Enviar", b = "[900,2000,1060,2150]", clicable = true)

    private class RespondedorFalso(val falla: Exception? = null) : Respondedor {
        val textos = ArrayList<String>()
        override fun responder(texto: String) { falla?.let { throw it }; textos.add(texto) }
    }

    private fun shellConPantalla(xml: () -> String) = FalsoShell { c ->
        if (c.contains("uiautomator dump")) ok(xml()) else ok()
    }

    private fun enviador(shell: FalsoShell, estado: EstadoNotificaciones) = EnviadorWhatsApp(shell, estado, RelojFalso())

    // ---------------- Enviador ----------------

    @Test fun metodoAUsaResponderYNoTocaLaInterfaz() {
        val estado = EstadoNotificaciones()
        val r = RespondedorFalso()
        estado.registrar(chat, HiloChat(r, null, System.currentTimeMillis()), emptyList())
        val shell = FalsoShell()
        val res = enviador(shell, estado).enviar(AccionEnvio.Texto(chat, "Hola"))
        assertTrue(res.ok); assertTrue(res.nota.contains("método A"))
        assertEquals(listOf("Hola"), r.textos)
        assertTrue(shell.comandos.isEmpty())
    }

    @Test fun sinAccionVigenteCaeAMetodoB() {
        val shell = shellConPantalla { volcado(caja(botonEnviar)) }
        val res = enviador(shell, EstadoNotificaciones()).enviar(AccionEnvio.Texto(chat, "¿Cómo estás?"))
        assertTrue(res.nota, res.ok)
        assertTrue(res.nota.contains("método B tras A"))
        val abrir = shell.texto().first { it.startsWith("am start") }
        assertTrue(abrir.contains("https://wa.me/5491155550000?text=%C2%BFC%C3%B3mo%20est%C3%A1s%3F"))
        assertTrue(shell.hubo("input tap 980 2075"))
    }

    @Test fun responderCaducadoCaeAB() {
        val estado = EstadoNotificaciones()
        estado.registrar(chat, HiloChat(RespondedorFalso(RespuestaNoVigente("caducó")), null, System.currentTimeMillis()), emptyList())
        val shell = shellConPantalla { volcado(caja(botonEnviar)) }
        val res = enviador(shell, estado).enviar(AccionEnvio.Texto(chat, "Hola"))
        assertTrue(res.ok); assertTrue(res.nota.contains("caducó"))
        assertNull("el respondedor caducado se olvida", estado.hilo(chat)!!.respondedor)
    }

    @Test fun metodoBFallaSiNuncaApareceElBoton() {
        val shell = shellConPantalla { volcado(caja(hoja("x:id/y", "nada"))) }
        val res = enviador(shell, EstadoNotificaciones()).enviar(AccionEnvio.Texto(chat, "Hola"))
        assertFalse(res.ok)
        assertFalse(shell.hubo("input tap"))
    }

    @Test fun metodoBSinShizukuNiJid() {
        assertFalse(enviador(FalsoShell(disp = false), EstadoNotificaciones()).enviar(AccionEnvio.Texto(chat, "Hola")).ok)
        assertFalse(enviador(shellConPantalla { volcado(caja(botonEnviar)) }, EstadoNotificaciones()).enviar(AccionEnvio.Texto("Ana", "Hola")).ok)
    }

    @Test fun imagenValidaRutaYUsaIntentDeCompartir() {
        val shell = shellConPantalla { volcado(caja(botonEnviar)) }
        val e = enviador(shell, EstadoNotificaciones())
        for (mala in listOf("/sdcard/../x.jpg", "x.jpg", "/sdcard/doc.txt", "/sdcard/a';id.jpg")) {
            assertFalse(mala, e.enviar(AccionEnvio.Imagen(chat, mala)).ok)
        }
        assertTrue("las rutas inválidas no tocan el shell", shell.comandos.isEmpty())
        val res = e.enviar(AccionEnvio.Imagen(chat, "/sdcard/Pictures/foto 1.jpg", "Pie"))
        assertTrue(res.nota, res.ok)
        val c = shell.texto().first { it.startsWith("am start") }
        assertTrue(c.contains("-a android.intent.action.SEND") && c.contains("-t 'image/*'") && c.contains("-p com.whatsapp"))
        assertTrue(c.contains("--eu android.intent.extra.STREAM 'file:///sdcard/Pictures/foto%201.jpg'"))
        assertTrue(c.contains("-e jid '5491155550000@s.whatsapp.net'") && c.contains("--es android.intent.extra.TEXT 'Pie'"))
    }

    @Test fun imagenNoLegibleNoSeEnvia() {
        val shell = FalsoShell { c -> if (c.startsWith("test")) ResultadoShell(1, "") else ok() }
        assertFalse(enviador(shell, EstadoNotificaciones()).enviar(AccionEnvio.Imagen(chat, "/sdcard/a.jpg")).ok)
        assertFalse(shell.hubo("am start"))
    }

    // ---------------- Verificador ----------------

    private fun verificador(shell: FalsoShell, estado: EstadoNotificaciones, reloj: RelojFalso, notas: MutableList<String> = ArrayList()) =
        VerificadorWhatsApp(shell, estado, reloj, { notas.add(it) }, utc)

    @Test fun sinEvidenciaDevuelveFalse() {
        val reloj = RelojFalso()
        val notas = ArrayList<String>()
        val shell = shellConPantalla { volcado(caja(hoja("x:id/y", "otra cosa"))) }
        val v = verificador(shell, EstadoNotificaciones(), reloj, notas)
        assertFalse(v.verificar(AccionEnvio.Texto(chat, "Hola"), reloj.t, 8_000))
        assertTrue(notas.single().contains("SIN CONFIRMAR"))
        assertTrue("el tiempo se respeta", reloj.t - 1_700_000_000_000L <= 9_500)
    }

    @Test fun ecoConfirmaSinAbrirLaInterfaz() {
        val reloj = RelojFalso()
        val estado = EstadoNotificaciones(ahora = { reloj.t })
        val shell = shellConPantalla { error("no debe abrir la interfaz") }
        val notas = ArrayList<String>()
        reloj.dormir(500)
        estado.registrar(chat, null, listOf(TextoNotif("Hola", "Tú")))
        assertTrue(verificador(shell, estado, reloj, notas).verificar(AccionEnvio.Texto(chat, "Hola"), reloj.t - 400, 5_000))
        assertTrue(notas.single().contains("CONFIRMADA") && notas.single().contains("eco"))
        assertTrue(shell.comandos.isEmpty())
    }

    @Test fun ecoDeOtroRemitenteNoConfirma() {
        val reloj = RelojFalso()
        val estado = EstadoNotificaciones(ahora = { reloj.t })
        estado.registrar(chat, null, listOf(TextoNotif("Hola", "Ana")))
        val v = verificador(FalsoShell(disp = false), estado, reloj)
        assertFalse(v.verificar(AccionEnvio.Texto(chat, "Hola"), reloj.t - 100, 3_000))
    }

    @Test fun sinEcoCaeAlDumpYConfirmaConEstado() {
        val reloj = RelojFalso()
        val h = hhmm(reloj.t, utc)
        for (estadoDesc in listOf("Enviado", "Entregado", "Leído")) {
            val shell = shellConPantalla { volcado(caja(fila("Hola, ¿qué tal?", h, estadoDesc))) }
            val v = verificador(shell, EstadoNotificaciones(), reloj)
            assertTrue(estadoDesc, v.verificar(AccionEnvio.Texto(chat, "Hola, ¿qué tal?"), reloj.t, 10_000))
            assertTrue(v.ultima!!.evidencia.contains(estadoDesc.lowercase()))
            assertEquals("B-interfaz", v.ultima!!.estrategia)
        }
    }

    @Test fun dumpPendienteTextoParecidoOSinEstadoNoConfirman() {
        val reloj = RelojFalso()
        val h = hhmm(reloj.t, utc)
        val casos = mapOf(
            "pendiente" to fila("Hola", h, "Pendiente"),
            "parecido" to fila("Hola mundo", h, "Leído"),
            "sin icono" to fila("Hola", h, null),
            "hora anterior" to fila("Hola", hhmm(reloj.t - 3 * 3_600_000L, utc), "Leído"),
        )
        for ((nombre, fila) in casos) {
            val v = verificador(shellConPantalla { volcado(caja(fila)) }, EstadoNotificaciones(), reloj)
            assertFalse(nombre, v.verificar(AccionEnvio.Texto(chat, "Hola"), reloj.t, 6_000))
        }
    }

    @Test fun pendienteQueLuegoSeEnviaConfirma() {
        val reloj = RelojFalso()
        val h = hhmm(reloj.t, utc)
        var lecturas = 0
        val shell = shellConPantalla { lecturas++; volcado(caja(fila("Hola", h, if (lecturas < 3) "Pendiente" else "Enviado"))) }
        assertTrue(verificador(shell, EstadoNotificaciones(), reloj).verificar(AccionEnvio.Texto(chat, "Hola"), reloj.t, 20_000))
        assertTrue(lecturas >= 3)
    }

    @Test fun imagenSoloPorDump() {
        val reloj = RelojFalso()
        val h = hhmm(reloj.t, utc)
        val img = caja(
            hoja("com.whatsapp:id/image", desc = "Imagen", b = "[100,900,900,1500]") +
                hoja("com.whatsapp:id/date", h) + hoja("com.whatsapp:id/status", desc = "Enviado"),
        )
        val shell = shellConPantalla { volcado(caja(img)) }
        assertTrue(verificador(shell, EstadoNotificaciones(), reloj).verificar(AccionEnvio.Imagen(chat, "/sdcard/a.jpg"), reloj.t, 8_000))
        val vacio = shellConPantalla { volcado(caja(hoja("x:id/y", "chat vacío"))) }
        assertFalse(verificador(vacio, EstadoNotificaciones(), reloj).verificar(AccionEnvio.Imagen(chat, "/sdcard/a.jpg"), reloj.t, 4_000))
    }

    @Test fun sinShizukuNiEcoEsFalse() {
        val reloj = RelojFalso()
        assertFalse(verificador(FalsoShell(disp = false), EstadoNotificaciones(), reloj).verificar(AccionEnvio.Texto(chat, "Hola"), reloj.t, 2_000))
    }

    @Test fun timeoutNoPositivoEsFalse() {
        val reloj = RelojFalso()
        assertFalse(verificador(FalsoShell(), EstadoNotificaciones(), reloj).verificar(AccionEnvio.Texto(chat, "Hola"), reloj.t, 0))
    }
}
