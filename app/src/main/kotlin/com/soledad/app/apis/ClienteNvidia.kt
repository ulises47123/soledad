package com.soledad.app.apis

import com.soledad.app.nucleo.ClienteEmbeddings
import com.soledad.app.nucleo.ClienteModelo
import com.soledad.app.nucleo.ResultadoModelo
import com.soledad.app.nucleo.TurnoChat
import org.json.JSONArray
import org.json.JSONObject

/**
 * NVIDIA NIM (API compatible con OpenAI). Una sola petición por llamada, sin reintentos automáticos.
 * Los modelos Nemotron «piensan» si no se les dice lo contrario: se apaga con `chat_template_kwargs.enable_thinking=false`.
 */
class ClienteNvidia(
    private val http: ClienteHttp = ClienteHttpReal(),
    private val base: String = "https://integrate.api.nvidia.com/v1",
) : ClienteModelo, ClienteEmbeddings {
    private val modeloValido = Regex("[A-Za-z0-9._/-]+")

    /** Cuerpo de la petición de chat (separado para poder probarlo sin red). */
    fun cuerpoChat(modelo: String, sistema: String, turnos: List<TurnoChat>, maxTokens: Int, temperatura: Double): JSONObject {
        val mensajes = JSONArray().put(JSONObject().put("role", "system").put("content", sistema))
        for (t in turnos) {
            val rol = if (t.rol == "model" || t.rol == "assistant") "assistant" else "user"
            mensajes.put(JSONObject().put("role", rol).put("content", t.texto))
        }
        return JSONObject()
            .put("model", modelo)
            .put("messages", mensajes)
            .put("max_tokens", maxTokens)
            .put("temperature", temperatura)
            .put("stream", false)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
    }

    override fun generar(clave: String, modelo: String, sistema: String, turnos: List<TurnoChat>, maxTokens: Int, temperatura: Double): ResultadoModelo {
        if (clave.isBlank()) return ResultadoModelo(null, 0, 0, "Falta la clave de NVIDIA")
        val m = modelo.trim()
        if (!modeloValido.matches(m)) return ResultadoModelo(null, 0, 0, "Nombre de modelo no válido")
        if (turnos.isEmpty()) return ResultadoModelo(null, 0, 0, "No hay mensajes que enviar")
        return try {
            val r = http.post(
                "$base/chat/completions", cabeceras(clave),
                cuerpoChat(m, sistema, turnos, maxTokens, temperatura).toString(), 10_000, 45_000,
            )
            if (r.codigo !in 200..299) return ResultadoModelo(null, 0, 0, descripcionHttp(r.codigo, mensajeError(r.cuerpo)))
            val j = JSONObject(r.cuerpo)
            val uso = j.optJSONObject("usage")
            val ent = uso?.optInt("prompt_tokens", 0) ?: 0
            val sal = uso?.optInt("completion_tokens", 0) ?: 0
            val texto = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()
            val limpio = sinPensamiento(texto)
            if (limpio.isBlank()) ResultadoModelo(null, ent, sal, "NVIDIA no devolvió texto")
            else ResultadoModelo(limpio, ent, sal, null)
        } catch (e: java.io.IOException) {
            ResultadoModelo(null, 0, 0, "Sin conexión o tiempo agotado: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            ResultadoModelo(null, 0, 0, "Respuesta ilegible de NVIDIA: ${e.javaClass.simpleName}")
        }
    }

    override fun vectores(clave: String, modelo: String, textos: List<String>, tipo: String): List<FloatArray>? {
        if (clave.isBlank() || textos.isEmpty()) return null
        return try {
            val cuerpo = JSONObject()
                .put("model", modelo)
                .put("input", JSONArray(textos))
                .put("input_type", if (tipo == "query") "query" else "passage")
                .put("encoding_format", "float")
                .put("truncate", "END")
            val r = http.post("$base/embeddings", cabeceras(clave), cuerpo.toString(), 10_000, 30_000)
            if (r.codigo !in 200..299) return null
            val datos = JSONObject(r.cuerpo).getJSONArray("data")
            val salida = arrayOfNulls<FloatArray>(textos.size)
            for (i in 0 until datos.length()) {
                val d = datos.getJSONObject(i)
                val e = d.getJSONArray("embedding")
                val v = FloatArray(e.length()) { e.getDouble(it).toFloat() }
                salida[d.optInt("index", i)] = v
            }
            if (salida.any { it == null }) null else salida.map { it!! }
        } catch (e: Exception) {
            null
        }
    }

    private fun cabeceras(clave: String) = mapOf(
        "Content-Type" to "application/json; charset=utf-8",
        "Accept" to "application/json",
        "Authorization" to "Bearer $clave",
    )

    private fun mensajeError(cuerpo: String): String = try {
        val j = JSONObject(cuerpo)
        j.optJSONObject("error")?.optString("message") ?: j.optString("detail", j.optString("message", cuerpo.take(120)))
    } catch (e: Exception) { cuerpo.take(120) }

    private fun descripcionHttp(codigo: Int, msg: String): String {
        val pista = when (codigo) {
            400 -> "petición inválida"
            401, 403 -> "clave rechazada"
            404 -> "modelo no disponible"
            429 -> "demasiadas peticiones"
            in 500..599 -> "servicio no disponible"
            else -> "error"
        }
        return "NVIDIA $codigo ($pista): ${msg.take(160)}"
    }

    companion object {
        /** Por si el modelo igual devuelve su razonamiento entre etiquetas. */
        fun sinPensamiento(t: String): String =
            t.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "").trim()
    }
}
