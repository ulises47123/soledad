package com.soledad.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.soledad.app.lista.ContactoAgenda
import com.soledad.app.lista.Nombres

/**
 * Lectura de la agenda del teléfono para elegir la lista blanca. Solo nombre visible y número (para homónimos).
 * Nada de esto se guarda, se registra ni se manda a la IA: solo se guardan los nombres que el usuario marca.
 */
object Agenda {
    fun permitido(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun leer(ctx: Context): List<ContactoAgenda> {
        if (!permitido(ctx)) return emptyList()
        val filas = ArrayList<ContactoAgenda>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) filas += ContactoAgenda(c.getString(0).orEmpty(), c.getString(1).orEmpty())
            }
        } catch (_: Exception) { return emptyList() }
        return Nombres.agrupar(filas)
    }
}
