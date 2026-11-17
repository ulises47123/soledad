package com.soledad.app.apis

import com.soledad.app.nucleo.TurnoChat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClienteNvidiaTest {
    private class HttpFalso(val codigo: Int, val cuerpo: String) : ClienteHttp {
        var url = ""; var enviado = ""; var cabeceras: Map<String, String> = emptyMap()
        override fun post(url: String, cabeceras: Map<String, String>, cuerpo: String, conexionMs: Int, lecturaMs: Int): RespuestaHttp {
            this.url = url; this.enviado = cuerpo; this.cabeceras = cabeceras
            return RespuestaHttp(codigo, this.cuerpo)
        }
    }

    private val ok = """{"choices":[{"message":{"role":"assistant","content":"Hola, ahora no puedo."}}],"usage":{"prompt_tokens":30,"completion_tokens":6}}"""

    @Test fun pideSinPensamientoYConRolesCorrectos() {
        val http = HttpFalso(200, ok)
        val r = ClienteNvidia(http).generar("CLAVE", "nvidia/nemotron-3.5-super-vl-120b-a12b", "SIS",
            listOf(TurnoChat("user", "a"), TurnoChat("model", "b"), TurnoChat("user", "c")), 200, 0.5)
        assertEquals("Hola, ahora no puedo.", r.texto)
        assertEquals(30, r.tokensEntrada); assertEquals(6, r.tokensSalida)
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", http.url)
        assertEquals("Bearer CLAVE", http.cabeceras["Authorization"])
        val j = JSONObject(http.enviado)
        assertFalse(j.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertEquals("nvidia/nemotron-3.5-super-vl-120b-a12b", j.getString("model"))
        val roles = (0 until j.getJSONArray("messages").length()).map { j.getJSONArray("messages").getJSONObject(it).getString("role") }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
    }

    @Test fun errorHttpNoExponeLaClave() {
        val r = ClienteNvidia(HttpFalso(401, """{"detail":"Unauthorized"}""")).generar("SECRETA-123", "m/x", "s", listOf(TurnoChat("user", "a")), 50, 0.1)
        assertNull(r.texto)
        assertTrue(r.error!!.contains("401"))
        assertFalse(r.error!!.contains("SECRETA"))
    }

    @Test fun quitaElRazonamientoSiAparece() {
        assertEquals("Listo.", ClienteNvidia.sinPensamiento("<think>pienso…</think>\nListo."))
    }

    @Test fun embeddingsEnOrden() {
        val http = HttpFalso(200, """{"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}""")
        val v = ClienteNvidia(http).vectores("K", "nvidia/nemotron-3.5-embed-1b", listOf("a", "b"), "passage")!!
        assertEquals(1.0f, v[0][0]); assertEquals(1.0f, v[1][1])
        assertEquals("passage", JSONObject(http.enviado).getString("input_type"))
        assertNull(ClienteNvidia(HttpFalso(500, "x")).vectores("K", "m", listOf("a"), "query"))
    }
}
