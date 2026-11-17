package com.soledad.app.rag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soledad.app.nucleo.TurnoChat

/** Almacenamiento del RAG: fuentes y fragmentos, historial por chat, caché de respuestas y contador diario. */
interface AlmacenRag {
    fun agregarFuente(titulo: String, fragmentos: List<String>, ahoraMs: Long): Long
    fun borrarFuente(id: Long)
    fun fuentes(): List<Fuente>
    fun fragmentos(): List<Fragmento>
    /** Cambia cada vez que cambia la base; permite invalidar el índice en memoria. */
    fun version(): Long

    /** Últimos `n` turnos del chat, del más viejo al más nuevo. */
    fun turnos(chatId: String, n: Int): List<TurnoChat>
    fun guardarTurno(chatId: String, rol: String, texto: String, ahoraMs: Long)

    /** Respuesta en caché creada en o después de `desdeMs`, o null. */
    fun cacheObtener(clave: String, desdeMs: Long): String?
    fun cachePoner(clave: String, texto: String, ahoraMs: Long)

    fun sumarTokens(dia: String, entrada: Int, salida: Int)
    /** (entrada, salida) del día. */
    fun tokens(dia: String): Pair<Int, Int>

    /** Embedding guardado de un fragmento para ese modelo, o null. */
    fun vector(fragmentoId: Long, modelo: String): FloatArray?
    fun guardarVector(fragmentoId: Long, modelo: String, v: FloatArray)
}

class AlmacenMemoria : AlmacenRag {
    private var sigId = 1L
    private var ver = 0L
    private val fuentes = LinkedHashMap<Long, Pair<Fuente, List<Fragmento>>>()
    private val historial = HashMap<String, MutableList<TurnoChat>>()
    private val cache = HashMap<String, Pair<String, Long>>()
    private val tok = HashMap<String, Pair<Int, Int>>()
    private val vec = HashMap<String, FloatArray>()

    @Synchronized override fun agregarFuente(titulo: String, fragmentos: List<String>, ahoraMs: Long): Long {
        val id = sigId++
        val frs = fragmentos.map { Fragmento(sigId++, id, it) }
        fuentes[id] = Fuente(id, titulo, ahoraMs, frs.size) to frs
        ver++
        return id
    }
    @Synchronized override fun borrarFuente(id: Long) { if (fuentes.remove(id) != null) ver++ }
    @Synchronized override fun fuentes() = fuentes.values.map { it.first }
    @Synchronized override fun fragmentos() = fuentes.values.flatMap { it.second }
    @Synchronized override fun version() = ver
    @Synchronized override fun turnos(chatId: String, n: Int) = historial[chatId].orEmpty().takeLast(n.coerceAtLeast(0))
    @Synchronized override fun guardarTurno(chatId: String, rol: String, texto: String, ahoraMs: Long) {
        historial.getOrPut(chatId) { ArrayList() }.add(TurnoChat(rol, texto))
    }
    @Synchronized override fun cacheObtener(clave: String, desdeMs: Long) = cache[clave]?.takeIf { it.second >= desdeMs }?.first
    @Synchronized override fun cachePoner(clave: String, texto: String, ahoraMs: Long) { cache[clave] = texto to ahoraMs }
    @Synchronized override fun sumarTokens(dia: String, entrada: Int, salida: Int) {
        val (e, s) = tok[dia] ?: (0 to 0)
        tok[dia] = (e + entrada) to (s + salida)
    }
    @Synchronized override fun tokens(dia: String) = tok[dia] ?: (0 to 0)
    @Synchronized override fun vector(fragmentoId: Long, modelo: String) = vec["$fragmentoId|$modelo"]
    @Synchronized override fun guardarVector(fragmentoId: Long, modelo: String, v: FloatArray) { vec["$fragmentoId|$modelo"] = v }
}

/** SQLite en la app (archivo propio `rag.db`). No cubierto por tests JVM. */
class AlmacenSqlite(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "rag.db", null, 1), AlmacenRag {
    @Volatile private var ver = 0L

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fuentes(id INTEGER PRIMARY KEY AUTOINCREMENT, titulo TEXT NOT NULL, creada INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE fragmentos(id INTEGER PRIMARY KEY AUTOINCREMENT, fuente_id INTEGER NOT NULL, texto TEXT NOT NULL)")
        db.execSQL("CREATE INDEX ix_frag_fuente ON fragmentos(fuente_id)")
        db.execSQL("CREATE TABLE historial(id INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT NOT NULL, rol TEXT NOT NULL, texto TEXT NOT NULL, ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX ix_hist_chat ON historial(chat, id)")
        db.execSQL("CREATE TABLE cache(clave TEXT PRIMARY KEY, texto TEXT NOT NULL, ms INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tokens(dia TEXT PRIMARY KEY, entrada INTEGER NOT NULL, salida INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE vectores(fragmento_id INTEGER NOT NULL, modelo TEXT NOT NULL, v BLOB NOT NULL, PRIMARY KEY(fragmento_id, modelo))")
    }
    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) {}

    override fun agregarFuente(titulo: String, fragmentos: List<String>, ahoraMs: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("fuentes", null, ContentValues().apply { put("titulo", titulo); put("creada", ahoraMs) })
            for (t in fragmentos) db.insertOrThrow("fragmentos", null, ContentValues().apply { put("fuente_id", id); put("texto", t) })
            db.setTransactionSuccessful()
            ver++
            return id
        } finally { db.endTransaction() }
    }

    override fun borrarFuente(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM vectores WHERE fragmento_id IN (SELECT id FROM fragmentos WHERE fuente_id=?)", arrayOf<Any>(id))
            db.delete("fragmentos", "fuente_id=?", arrayOf(id.toString()))
            db.delete("fuentes", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            ver++
        } finally { db.endTransaction() }
    }

    override fun fuentes(): List<Fuente> = readableDatabase.rawQuery(
        "SELECT f.id, f.titulo, f.creada, (SELECT COUNT(*) FROM fragmentos g WHERE g.fuente_id=f.id) FROM fuentes f ORDER BY f.id", null,
    ).use { c -> buildList { while (c.moveToNext()) add(Fuente(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3))) } }

    override fun fragmentos(): List<Fragmento> = readableDatabase.rawQuery("SELECT id, fuente_id, texto FROM fragmentos ORDER BY id", null)
        .use { c -> buildList { while (c.moveToNext()) add(Fragmento(c.getLong(0), c.getLong(1), c.getString(2))) } }

    override fun version() = ver

    override fun turnos(chatId: String, n: Int): List<TurnoChat> {
        if (n <= 0) return emptyList()
        return readableDatabase.rawQuery("SELECT rol, texto FROM historial WHERE chat=? ORDER BY id DESC LIMIT ?", arrayOf(chatId, n.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(TurnoChat(c.getString(0), c.getString(1))) } }.reversed()
    }

    override fun guardarTurno(chatId: String, rol: String, texto: String, ahoraMs: Long) {
        writableDatabase.insert("historial", null, ContentValues().apply { put("chat", chatId); put("rol", rol); put("texto", texto); put("ms", ahoraMs) })
        // El historial solo sirve para el prompt: se poda lo que supera los 200 turnos por chat.
        writableDatabase.execSQL(
            "DELETE FROM historial WHERE chat=? AND id NOT IN (SELECT id FROM historial WHERE chat=? ORDER BY id DESC LIMIT 200)",
            arrayOf(chatId, chatId),
        )
    }

    override fun cacheObtener(clave: String, desdeMs: Long): String? = readableDatabase
        .rawQuery("SELECT texto FROM cache WHERE clave=? AND ms>=?", arrayOf(clave, desdeMs.toString()))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    override fun cachePoner(clave: String, texto: String, ahoraMs: Long) {
        writableDatabase.insertWithOnConflict("cache", null, ContentValues().apply { put("clave", clave); put("texto", texto); put("ms", ahoraMs) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun sumarTokens(dia: String, entrada: Int, salida: Int) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO tokens(dia, entrada, salida) VALUES(?,0,0)", arrayOf(dia))
        db.execSQL("UPDATE tokens SET entrada=entrada+?, salida=salida+? WHERE dia=?", arrayOf<Any>(entrada, salida, dia))
    }

    override fun tokens(dia: String): Pair<Int, Int> = readableDatabase.rawQuery("SELECT entrada, salida FROM tokens WHERE dia=?", arrayOf(dia))
        .use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else 0 to 0 }

    override fun vector(fragmentoId: Long, modelo: String): FloatArray? = readableDatabase
        .rawQuery("SELECT v FROM vectores WHERE fragmento_id=? AND modelo=?", arrayOf(fragmentoId.toString(), modelo))
        .use { if (it.moveToFirst()) bytesAVector(it.getBlob(0)) else null }

    override fun guardarVector(fragmentoId: Long, modelo: String, v: FloatArray) {
        writableDatabase.insertWithOnConflict("vectores", null, ContentValues().apply {
            put("fragmento_id", fragmentoId); put("modelo", modelo); put("v", vectorABytes(v))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun vectorABytes(v: FloatArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(v.size * 4)
        v.forEach { b.putFloat(it) }
        return b.array()
    }

    private fun bytesAVector(b: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(b)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }
}
