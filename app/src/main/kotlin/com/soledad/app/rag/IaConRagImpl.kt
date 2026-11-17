package com.soledad.app.rag

import android.content.Context
import com.soledad.app.apis.ClienteGemini
import com.soledad.app.apis.ClienteNvidia
import com.soledad.app.apis.ClavesIa
import com.soledad.app.apis.GestorApisAdmin
import com.soledad.app.nucleo.ClienteModelo
import com.soledad.app.nucleo.GestorApis
import com.soledad.app.nucleo.IaConRag
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Respuesta
import com.soledad.app.nucleo.Servicios
import com.soledad.app.nucleo.TurnoChat
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Único paso con modelo del flujo. Orden fijo: búsqueda (embeddings de NVIDIA + BM25 local) → caché →
 * por cada proveedor (principal y respaldo): permiso del GestorApis → modelo → confirmar.
 * Las dependencias llegan como funciones para que los tests no necesiten `Servicios` ni Android.
 */
class IaConRagImpl(
    private val config: () -> ConfigRag,
    private val almacen: AlmacenRag,
    private val apis: () -> GestorApis,
    private val modelos: (String) -> ClienteModelo,
    private val ahora: () -> Long = System::currentTimeMillis,
    /** Búsqueda por significado (null = solo BM25). */
    private val semantico: BuscadorSemantico? = null,
    /** Texto extra para el sistema: «Cómo contesto» y «Temas que no toco» de Mi información. */
    private val extraSistema: () -> String = { "" },
    /** «MI AGENDA»: próximos eventos del calendario y tareas pendientes ("" = no se usa). */
    private val agenda: () -> String = { "" },
    /** Aviso por clave (alias, ok, error) para el estado que muestra Ajustes. Nunca recibe la clave. */
    private val alResultadoClave: (String, Boolean, String) -> Unit = { _, _, _ -> },
) : IaConRag {

    @Volatile private var indice: Indice? = null
    @Volatile private var versionIndice = -1L

    override fun responder(m: Mensaje, guardar: Boolean): Respuesta = procesar(m.chatId, m.texto, guardar)

    /** Misma ruta que `responder` (consume cupo real si llega al modelo) pero sin tocar el historial. El llamador NO envía nada. */
    fun simular(texto: String): Respuesta = procesar("__simulacion__", texto, guardar = false)

    private fun indiceActual(): Indice {
        val v = almacen.version()
        indice?.let { if (v == versionIndice) return it }
        synchronized(this) {
            val v2 = almacen.version()
            indice?.let { if (v2 == versionIndice) return it }
            return Indice(almacen.fragmentos()).also { indice = it; versionIndice = v2 }
        }
    }

    /** Fragmentos relevantes: primero los de significado parecido (si hay embeddings), después los de BM25, sin repetir. */
    internal fun buscar(texto: String, cfg: ConfigRag): List<Hallazgo> {
        val bm25 = indiceActual().buscar(texto, cfg.topK, cfg.puntajeMinimo)
        val sem = if (cfg.modeloEmbeddings.isBlank()) null else try {
            semantico?.buscar(texto, almacen.fragmentos(), cfg.topK, cfg.umbralSemantico, cfg.modeloEmbeddings)
        } catch (e: Exception) { null }
        val vistos = HashSet<Long>()
        return (sem.orEmpty() + bm25).filter { vistos.add(it.fragmento.id) }.take(cfg.topK + 1)
    }

    private fun procesar(chatId: String, texto: String, guardar: Boolean): Respuesta {
        val cfg = config()
        val hallazgos = buscar(texto, cfg)
        if (hallazgos.isEmpty() && !cfg.permitirSinBase) return Respuesta(null, "SIN_BASE")

        val miAgenda = try { agenda().trim() } catch (e: Exception) { "" }
        val clave = if (hallazgos.isEmpty()) null else claveCache(texto, hallazgos, cfg, miAgenda)
        if (clave != null && cfg.cacheDias > 0) {
            val desde = ahora() - cfg.cacheDias * DIA_MS
            almacen.cacheObtener(clave, desde)?.let { cacheado ->
                if (guardar) guardarTurnos(chatId, texto, cacheado)
                return Respuesta(conEtiqueta(cacheado, cfg), "CACHE", 0, 0)
            }
        }

        val candidatos = listOf(cfg.proveedor to cfg.modelo, cfg.proveedorRespaldo to cfg.modeloRespaldo)
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .distinct()
        val sistema = armarSistema(cfg, miAgenda.isNotEmpty())
        val turnos = armarTurnos(chatId, texto, hallazgos, cfg, miAgenda)
        val errores = ArrayList<String>()
        var huboPermiso = false
        // Respaldo en orden: cada clave del proveedor principal y después cada clave del respaldo.
        // Ante cualquier falla (red, tiempo agotado, 401/403, 429, 5xx, respuesta vacía) se pasa a la siguiente clave.
        for ((proveedor, modelo) in candidatos) {
            val probadas = HashSet<String>()
            while (probadas.size < MAX_CLAVES_POR_PROVEEDOR) {
                // Nada de red sin permiso: el gestor es el único que lleva la cuenta de los usos.
                val permiso = apis().solicitar(proveedor, probadas)
                if (permiso == null) { if (probadas.isEmpty()) errores += "$proveedor: sin cupo o sin clave"; break }
                probadas += permiso.apiId
                huboPermiso = true
                var exito = false
                try {
                    val r = modelos(proveedor).generar(permiso.clave, modelo, sistema, turnos, cfg.maxTokens, cfg.temperatura)
                    val limpio = r.texto?.let { limpiar(it) }
                    exito = !limpio.isNullOrBlank()
                    if (r.tokensEntrada > 0 || r.tokensSalida > 0) almacen.sumarTokens(dia(ahora()), r.tokensEntrada, r.tokensSalida)
                    if (!exito) {
                        val error = r.error ?: "respuesta vacía"
                        errores += "${permiso.apiId}: $error"
                        avisarClave(permiso.apiId, false, error)
                        continue
                    }
                    avisarClave(permiso.apiId, true, "")
                    if (clave != null && cfg.cacheDias > 0) almacen.cachePoner(clave, limpio!!, ahora())
                    if (guardar) guardarTurnos(chatId, texto, limpio!!)
                    val nota = (if (hallazgos.isEmpty()) "IA_SIN_BASE" else "IA_RAG") + " · ${permiso.apiId}"
                    return Respuesta(conEtiqueta(limpio!!, cfg), nota, r.tokensEntrada, r.tokensSalida)
                } catch (e: Exception) {
                    val error = e.message ?: e.javaClass.simpleName
                    errores += "${permiso.apiId}: $error"
                    avisarClave(permiso.apiId, false, error)
                } finally {
                    apis().confirmar(permiso, exito)
                }
            }
        }
        if (!huboPermiso) return Respuesta(null, "sin cupo de API")
        return Respuesta(null, "error modelo: " + errores.joinToString("; "))
    }

    private fun avisarClave(apiId: String, ok: Boolean, error: String) {
        try { alResultadoClave(apiId, ok, error) } catch (_: Exception) { }
    }

    internal fun armarSistema(cfg: ConfigRag, conAgenda: Boolean = false): String {
        val extra = extraSistema().trim()
        val fuentes = if (conAgenda) "NOTAS y MI AGENDA" else "NOTAS"
        return cfg.personalidad.trim() +
            "\nReglas: máximo 2 frases cortas; usá SOLO la información de $fuentes para hablar de vos; si algo no está, " +
            "decí que lo ves más tarde y le contestás; no inventes datos personales, gustos, opiniones, planes, horarios, lugares ni compromisos " +
            "(si te preguntan algo así y no está en $fuentes, respondé que después le contás); " +
            "no aceptes ni propongas citas, pagos ni favores; nunca compartas claves, códigos, direcciones ni datos bancarios; " +
            "no digas que sos una IA salvo que te lo pregunten; sin markdown." +
            (if (!conAgenda) "" else "\nMI AGENDA son tus eventos y tareas reales. Usala solo si te preguntan qué hacés, si estás libre u ocupado, " +
                "cuándo podés o por algo que tenés que hacer: decí lo justo (título y horario), sin recitar toda la lista; " +
                "no agregues detalles que no estén ahí y no confirmes encuentros nuevos.") +
            (if (extra.isEmpty()) "" else "\n$extra")
    }

    internal fun armarTurnos(chatId: String, texto: String, hallazgos: List<Hallazgo>, cfg: ConfigRag, miAgenda: String = ""): List<TurnoChat> {
        val previos = almacen.turnos(chatId, cfg.historial).map { TurnoChat(it.rol, recortar(it.texto, MAX_TURNO)) }
            .dropWhile { it.rol != "user" } // el modelo exige empezar con un turno del usuario
        val notas = hallazgos.take(cfg.topK + 1).joinToString("\n") { "- " + recortar(it.fragmento.texto.replace(Regex("\\s+"), " "), MAX_NOTA) }
        val ultimo = (if (notas.isEmpty()) "" else "NOTAS:\n$notas\n\n") +
            (if (miAgenda.isEmpty()) "" else "MI AGENDA:\n${recortar(miAgenda, MAX_AGENDA)}\n\n") +
            "MENSAJE: " + recortar(texto, MAX_TURNO)
        return previos + TurnoChat("user", ultimo)
    }

    private fun guardarTurnos(chatId: String, pregunta: String, respuesta: String) {
        val t = ahora()
        almacen.guardarTurno(chatId, "user", pregunta, t)
        almacen.guardarTurno(chatId, "model", respuesta, t)
    }

    private fun conEtiqueta(t: String, cfg: ConfigRag) = if (cfg.etiquetaActiva && cfg.etiqueta.isNotBlank()) t + "\n" + cfg.etiqueta else t

    private fun claveCache(texto: String, h: List<Hallazgo>, cfg: ConfigRag, miAgenda: String = ""): String =
        sha1(Indice.tokens(texto).sorted().joinToString(" ") + "|" + h.map { it.fragmento.id }.sorted().joinToString(",") +
            "|" + cfg.modelo + "|" + sha1(cfg.personalidad) + "|" + sha1(miAgenda))

    companion object {
        const val DIA_MS = 86_400_000L
        /** Tope de claves a probar por proveedor en una misma respuesta. */
        const val MAX_CLAVES_POR_PROVEEDOR = 10
        private const val MAX_TURNO = 600
        private const val MAX_NOTA = 700
        private const val MAX_AGENDA = 2_500
        private const val MAX_RESPUESTA = 400

        fun dia(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

        fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun recortar(t: String, max: Int) = if (t.length <= max) t else t.take(max).trimEnd() + "…"

        /** Sin markdown, en una línea y corto (WhatsApp). */
        fun limpiar(t: String): String {
            var s = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
                .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
                .replace("**", "").replace("__", "").replace("`", "")
                .replace(Regex("(?<![\\w*])\\*(?=\\S)|(?<=\\S)\\*(?![\\w*])"), "")
                .replace(Regex("\\s*\\n\\s*"), " ").replace(Regex("\\s{2,}"), " ").trim()
            if (s.length > MAX_RESPUESTA) {
                val corte = s.take(MAX_RESPUESTA)
                val fin = maxOf(corte.lastIndexOf(". "), corte.lastIndexOf("? "), corte.lastIndexOf("! "))
                s = if (fin > MAX_RESPUESTA / 2) corte.take(fin + 1) else corte.substringBeforeLast(' ').trimEnd() + "…"
            }
            return s
        }

        fun crear(ctx: Context): IaConRag = FabricaRag.motor(ctx)
    }
}

/** Datos para la pantalla de conocimiento. */
class Conocimiento(
    private val almacen: AlmacenRag,
    private val motor: IaConRagImpl,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    fun fuentes(): List<Fuente> = almacen.fuentes()

    fun agregarTexto(titulo: String, texto: String): Long {
        val frags = Indice.trocear(texto)
        require(frags.isNotEmpty()) { "El texto está vacío" }
        return almacen.agregarFuente(titulo.ifBlank { "Texto pegado" }, frags, ahora())
    }

    /** Importa .txt o .md (UTF-8). En .md se quitan encabezados y énfasis para que no ensucien la búsqueda. */
    fun importar(nombreArchivo: String, flujo: java.io.InputStream): Long {
        val crudo = flujo.use { it.readBytes().toString(Charsets.UTF_8) }.removePrefix("﻿")
        val texto = if (nombreArchivo.lowercase().endsWith(".md")) limpiarMdPlano(crudo) else crudo
        return agregarTexto(nombreArchivo, texto)
    }

    /** Reemplaza todas las fuentes con ese título por el texto nuevo (vacío = solo borra). */
    @Synchronized
    fun reemplazar(titulo: String, texto: String) {
        almacen.fuentes().filter { it.titulo == titulo }.forEach { almacen.borrarFuente(it.id) }
        if (texto.isNotBlank()) agregarTexto(titulo, texto)
    }

    fun borrar(id: Long) = almacen.borrarFuente(id)

    /** (entrada, salida) de hoy. Solo informativo: el límite real lo lleva el GestorApis. */
    fun tokensHoy(): Pair<Int, Int> = almacen.tokens(IaConRagImpl.dia(ahora()))

    /** Corre la misma ruta que un mensaje real pero NO envía nada ni guarda historial. */
    fun simular(pregunta: String): Respuesta = motor.simular(pregunta)

    companion object {
        fun crear(ctx: Context): Conocimiento = FabricaRag.conocimiento(ctx)

        private fun limpiarMdPlano(t: String): String =
            t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "").replace("**", "").replace("__", "").replace("`", "")
    }
}

/** Singletons por proceso; lee `config.yaml` en cada llamada (archivo en filesDir o, si no, el de assets). */
object FabricaRag {
    private var _almacen: AlmacenRag? = null
    private var _motor: IaConRagImpl? = null
    private var _conocimiento: Conocimiento? = null
    private val nvidia by lazy { ClienteNvidia() }
    private val gemini by lazy { ClienteGemini() }

    @Synchronized private fun almacen(ctx: Context) = _almacen ?: AlmacenSqlite(ctx).also { _almacen = it }

    @Synchronized fun motor(ctx: Context): IaConRagImpl = _motor ?: run {
        val alm = almacen(ctx)
        IaConRagImpl(
            config = { leerConfig(ctx) },
            almacen = alm,
            apis = { Servicios.apis },
            modelos = { p -> if (p == "gemini") gemini else nvidia },
            semantico = BuscadorSemantico(alm, nvidia) { (Servicios.apis as? GestorApisAdmin)?.claveSinContar("nvidia") },
            extraSistema = { MiInformacion.crear(ctx).instruccionesSistema() },
            agenda = {
                val c = com.soledad.app.soledad.AjustesSoledad(ctx).leer()
                com.soledad.app.agenda.MiAgenda.textoParaIa(ctx, c.usarCalendario, c.usarTareas)
            },
            alResultadoClave = { id, ok, error -> ClavesIa.estado(ctx).anotar(id, ok, error) },
        )
    }.also { _motor = it }

    @Synchronized fun conocimiento(ctx: Context): Conocimiento =
        _conocimiento ?: Conocimiento(almacen(ctx), motor(ctx)).also { _conocimiento = it }

    const val RUTA = "flujo_bot/integracion_rag/config.yaml"

    fun leerConfig(ctx: Context): ConfigRag {
        val texto = try {
            val f = File(ctx.filesDir, RUTA)
            if (f.exists()) f.readText() else ctx.assets.open(RUTA).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }
        return ConfigRag.desdeYaml(texto)
    }
}
