package com.soledad.app.apis

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Almacén de claves de API por nombre. Las claves nunca se guardan en el YAML ni en el estado de cuotas. */
interface AlmacenSecretos {
    fun obtener(nombre: String): String?
    fun guardar(nombre: String, valor: String)
    fun borrar(nombre: String)
}

/** Cifra cada secreto con una clave AES-GCM que vive en el Android Keystore (rescatado de v1 Secreto.kt). */
class AlmacenKeystore(private val archivo: File) : AlmacenSecretos {
    private val alias = "agente_apis"

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun cifrar(texto: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, clave())
        return Base64.encodeToString(c.iv + c.doFinal(texto.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun descifrar(b64: String): String? = try {
        val todo = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, todo, 0, 12))
        String(c.doFinal(todo, 12, todo.size - 12), Charsets.UTF_8)
    } catch (e: Exception) { null }

    private fun leer(): JSONObject = try { JSONObject(archivo.readText()) } catch (e: Exception) { JSONObject() }

    private fun escribir(j: JSONObject) {
        archivo.parentFile?.mkdirs()
        val tmp = File(archivo.path + ".tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
    }

    @Synchronized override fun obtener(nombre: String): String? = leer().optString(nombre, "").takeIf { it.isNotEmpty() }?.let { descifrar(it) }
    @Synchronized override fun guardar(nombre: String, valor: String) { escribir(leer().put(nombre, cifrar(valor))) }
    @Synchronized override fun borrar(nombre: String) { escribir(leer().apply { remove(nombre) }) }
}
