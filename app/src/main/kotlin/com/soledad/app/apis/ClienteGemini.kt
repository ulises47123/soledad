package com.soledad.app.apis

import android.content.Context
import com.soledad.app.nucleo.ClienteModelo
import com.soledad.app.nucleo.ResultadoModelo
import com.soledad.app.nucleo.TurnoChat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RespuestaHttp(val codigo: Int, val cuerpo: String)

/** Capa HTTP detrás de una interfaz para poder falsearla en tests. */
interface ClienteHttp {
    fun post(url: String, cabeceras: Map<String, String>, cuerpo: String, conexionMs: Int, lecturaMs: Int): RespuestaHttp
}

class ClienteHttpReal : ClienteHttp {
    override fun post(url: String, cabeceras: Map<String, String>, cuerpo: String, conexionMs: Int, lecturaMs: Int): RespuestaHttp {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = conexionMs
            con.readTimeout = lecturaMs
            con.doOutput = true
            cabeceras.forEach { (k, v) -> con.setRequestProperty(k, v) }
            con.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            val txt = (if (codigo in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return RespuestaHttp(codigo, txt)
        } finally {
            con.disconnect()
        }
    }
}

/** Una sola petición por llamada, sin reintentos automáticos (cada reintento gastaría un uso y tokens). */
class ClienteGemini(
    private val http: ClienteHttp = ClienteHttpReal(),
    private val base: String = "https://generativelanguage.googleapis.com/v1beta/models",
) : ClienteModelo {
    private val modeloValido = Regex("[A-Za-z0-9._-]+")

    override fun generar(clave: String, modelo: String, sistema: String, turnos: List<TurnoChat>, maxTokens: Int, temperatura: Double): ResultadoModelo {
        if (clave.isBlank()) return ResultadoModelo(null, 0, 0, "Falta la clave de API")
        val m = modelo.trim().removePrefix("models/")
        if (!modeloValido.matches(m)) return ResultadoModelo(null, 0, 0, "Nombre de modelo no válido")
        if (turnos.isEmpty()) return ResultadoModelo(null, 0, 0, "No hay mensajes que enviar")

        val contenido = JSONArray()
        for (t in turnos) {
            val rol = if (t.rol == "model" || t.rol == "assistant") "model" else "user"
            contenido.put(JSONObject().put("role", rol).put("parts", JSONArray().put(JSONObject().put("text", t.texto))))
        }
        val config = JSONObject().put("maxOutputTokens", maxTokens).put("temperature", temperatura).put("candidateCount", 1)
        // Los modelos 2.5 «piensan» y esos tokens salen del mismo tope de salida.
        if (m.startsWith("gemini-2.5")) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val cuerpo = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sistema))))
            .put("contents", contenido)
            .put("generationConfig", config)

        return try {
            val r = http.post(
                "$base/$m:generateContent",
                mapOf("Content-Type" to "application/json; charset=utf-8", "x-goog-api-key" to clave),
                cuerpo.toString(), 10_000, 25_000,
            )
            if (r.codigo !in 200..299) {
                val msg = try { JSONObject(r.cuerpo).getJSONObject("error").getString("message") } catch (e: Exception) { r.cuerpo.take(120) }
                return ResultadoModelo(null, 0, 0, descripcionHttp(r.codigo, msg))
            }
            val j = JSONObject(r.cuerpo)
            val uso = j.optJSONObject("usageMetadata")
            val ent = uso?.optInt("promptTokenCount", 0) ?: 0
            val sal = (uso?.optInt("candidatesTokenCount", 0) ?: 0) + (uso?.optInt("thoughtsTokenCount", 0) ?: 0)
            val cand = j.optJSONArray("candidates")?.optJSONObject(0)
            val partes = cand?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (partes != null) for (i in 0 until partes.length()) sb.append(partes.getJSONObject(i).optString("text", ""))
            if (sb.isBlank()) ResultadoModelo(null, ent, sal, "Gemini no devolvió texto (${cand?.optString("finishReason") ?: "sin candidatos"})")
            else ResultadoModelo(sb.toString().trim(), ent, sal, null)
        } catch (e: java.io.IOException) {
            ResultadoModelo(null, 0, 0, "Sin conexión o tiempo agotado: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            ResultadoModelo(null, 0, 0, "Respuesta ilegible de Gemini: ${e.javaClass.simpleName}")
        }
    }

    private fun descripcionHttp(codigo: Int, msg: String): String {
        val pista = when (codigo) {
            400 -> "petición inválida"
            401, 403 -> "clave rechazada"
            429 -> "cuota agotada en Google"
            in 500..599 -> "servicio no disponible"
            else -> "error"
        }
        return "Gemini $codigo ($pista): ${msg.take(160)}"
    }

    companion object {
        fun crear(@Suppress("UNUSED_PARAMETER") ctx: Context): ClienteModelo = ClienteGemini()
    }
}
