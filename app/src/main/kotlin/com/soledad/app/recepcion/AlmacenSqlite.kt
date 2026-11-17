package com.soledad.app.recepcion

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soledad.app.nucleo.Cola
import com.soledad.app.nucleo.EstadoCola
import com.soledad.app.nucleo.Mensaje

/** Almacén SQLite de la cola (archivo `cola.db`). */
class AlmacenSqlite(ctx: Context) : AlmacenCola, SQLiteOpenHelper(ctx.applicationContext, "cola.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE cola(id TEXT PRIMARY KEY, chat_id TEXT, nombre TEXT, texto TEXT, grupo INTEGER,
               recibido_ms INTEGER, paquete TEXT, estado TEXT, intentos INTEGER, lease_hasta INTEGER,
               proximo_ms INTEGER, nota TEXT, creado_ms INTEGER, actualizado_ms INTEGER)""",
        )
        db.execSQL("CREATE INDEX cola_estado ON cola(estado)")
    }

    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) {}

    private fun valores(f: FilaCola) = ContentValues().apply {
        put("id", f.id); put("chat_id", f.mensaje.chatId); put("nombre", f.mensaje.nombre)
        put("texto", f.mensaje.texto); put("grupo", if (f.mensaje.grupo) 1 else 0)
        put("recibido_ms", f.mensaje.recibidoMs); put("paquete", f.mensaje.paquete)
        put("estado", f.estado.name); put("intentos", f.intentos); put("lease_hasta", f.leaseHasta)
        put("proximo_ms", f.proximoMs); put("nota", f.nota); put("creado_ms", f.creadoMs); put("actualizado_ms", f.actualizadoMs)
    }

    private fun fila(c: Cursor) = FilaCola(
        Mensaje(
            c.getString(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("chat_id")),
            c.getString(c.getColumnIndexOrThrow("nombre")), c.getString(c.getColumnIndexOrThrow("texto")),
            c.getInt(c.getColumnIndexOrThrow("grupo")) == 1, c.getLong(c.getColumnIndexOrThrow("recibido_ms")),
            c.getString(c.getColumnIndexOrThrow("paquete")),
        ),
        EstadoCola.valueOf(c.getString(c.getColumnIndexOrThrow("estado"))),
        c.getInt(c.getColumnIndexOrThrow("intentos")), c.getLong(c.getColumnIndexOrThrow("lease_hasta")),
        c.getLong(c.getColumnIndexOrThrow("proximo_ms")), c.getString(c.getColumnIndexOrThrow("nota")).orEmpty(),
        c.getLong(c.getColumnIndexOrThrow("creado_ms")), c.getLong(c.getColumnIndexOrThrow("actualizado_ms")),
    )

    override fun insertarSiNuevo(f: FilaCola): Boolean =
        writableDatabase.insertWithOnConflict("cola", null, valores(f), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    override fun obtener(id: String): FilaCola? =
        readableDatabase.query("cola", null, "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) fila(it) else null }

    override fun actualizar(f: FilaCola) {
        writableDatabase.update("cola", valores(f), "id=?", arrayOf(f.id))
    }

    override fun listar(estados: Set<EstadoCola>): List<FilaCola> {
        val marcas = estados.joinToString(",") { "?" }
        return readableDatabase.query("cola", null, "estado IN ($marcas)", estados.map { it.name }.toTypedArray(), null, null, "creado_ms").use { c ->
            buildList { while (c.moveToNext()) add(fila(c)) }
        }
    }

    override fun purgar(actualizadoAntesDe: Long, estados: Set<EstadoCola>): Int {
        val marcas = estados.joinToString(",") { "?" }
        return writableDatabase.delete(
            "cola", "actualizado_ms<? AND estado IN ($marcas)",
            arrayOf(actualizadoAntesDe.toString()) + estados.map { it.name },
        )
    }
}

/** Cola persistente en SQLite (`cola.db`). */
object ColaSqlite {
    fun crear(ctx: Context): ColaPersistente = ColaPersistente(AlmacenSqlite(ctx))
}
