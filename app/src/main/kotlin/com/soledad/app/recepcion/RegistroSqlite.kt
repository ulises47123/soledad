package com.soledad.app.recepcion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Registro

/** Registro del ciclo en SQLite (`registro.db`): marca de tiempo, id del mensaje, etapa y detalle. */
class RegistroSqlite private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "registro.db", null, 1), Registro {

    private var escrituras = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE registro(n INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, mensaje_id TEXT, etapa TEXT, detalle TEXT)")
        db.execSQL("CREATE INDEX registro_ts ON registro(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) {}

    @Synchronized
    override fun anotar(m: Mensaje?, etapa: String, detalle: String) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("mensaje_id", m?.id); put("etapa", etapa); put("detalle", detalle)
        }
        writableDatabase.insert("registro", null, v)
        if (++escrituras % 200 == 0) purgar(DIAS_RETENCION * 86_400_000L)
    }

    @Synchronized
    fun borrarTodo(): Int = writableDatabase.delete("registro", null, null)

    fun purgar(edadMs: Long): Int =
        writableDatabase.delete("registro", "ts<?", arrayOf((System.currentTimeMillis() - edadMs).toString()))

    /** Últimas entradas, más reciente primero (para la UI). Formato: "ts | mensaje | etapa | detalle". */
    @Synchronized
    fun ultimas(limite: Int = 100): List<String> =
        readableDatabase.query("registro", null, null, null, null, null, "n DESC", limite.toString()).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    "${c.getLong(1)} | ${c.getString(2) ?: "-"} | ${c.getString(3)} | ${c.getString(4)}",
                )
            }
        }

    companion object {
        const val DIAS_RETENCION = 14
        fun crear(ctx: Context): RegistroSqlite = RegistroSqlite(ctx)
    }
}
