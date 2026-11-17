package com.soledad.app.nucleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniYamlTest {

    @Test
    fun menu_con_lista_de_mapas() {
        val y = """
            # opciones del menú
            menu:
              - id: foto            # una imagen
                etiqueta: "Imagen al azar"
                tipo: imagen_aleatoria
                payload: /sdcard/misiiiii/imagenes
                orden: 1
              - id: pagina
                etiqueta: Mi página
                tipo: enlace
                payload:
                  url: "https://ejemplo.com/a?b=1#x"
                orden: 2
              - id: hola
                etiqueta: 'Saludo: ''hola'''
                tipo: texto_fijo
                payload: Hola, ¿cómo estás?
                orden: 3
        """.trimIndent()
        val m = MiniYaml.parse(y).mapa()["menu"].lista()
        assertEquals(3, m.size)
        assertEquals("foto", m[0].mapa()["id"])
        assertEquals("Imagen al azar", m[0].mapa()["etiqueta"])
        assertEquals(1, m[0].mapa()["orden"])
        assertEquals("https://ejemplo.com/a?b=1#x", m[1].mapa()["payload"].mapa()["url"])
        assertEquals("Saludo: 'hola'", m[2].mapa()["etiqueta"])
        assertEquals("Hola, ¿cómo estás?", m[2].mapa()["payload"])
    }

    @Test
    fun apis_con_variables_y_booleanos() {
        val y = """
            apis:
              - id: "gemini_principal"
                clave: "${'$'}{GEMINI_API_KEY_1}"
                limite_usos: 3
                ventana_reinicio: "24h"
                activa: true
              - id: gemini_respaldo
                clave: "${'$'}{GEMINI_API_KEY_2}"
                limite_usos: 3
                ventana_reinicio: diario_utc
                activa: false
        """.trimIndent()
        val a = MiniYaml.parse(y).mapa()["apis"].lista()
        assertEquals(2, a.size)
        assertEquals("${'$'}{GEMINI_API_KEY_1}", a[0].mapa()["clave"])
        assertEquals(3, a[0].mapa()["limite_usos"])
        assertEquals(true, a[0].mapa()["activa"])
        assertEquals(false, a[1].mapa()["activa"])
        assertEquals("diario_utc", a[1].mapa()["ventana_reinicio"])
    }

    @Test
    fun comando_listas_en_linea_y_nulos() {
        val y = """
            comando: "-/4"
            permitir_prefijo: false
            contactos_autorizados: [ "5491111111111", '5492222222222' ]
            vacio:
            nada: ~
            numeros:
              - 1
              - 2.5
        """.trimIndent()
        val m = MiniYaml.parse(y).mapa()
        assertEquals("-/4", m["comando"])
        assertEquals(false, m["permitir_prefijo"])
        assertEquals(listOf<Any?>("5491111111111", "5492222222222"), m["contactos_autorizados"])
        assertNull(m["vacio"])
        assertNull(m["nada"])
        assertEquals(listOf<Any?>(1, 2.5), m["numeros"])
    }

    @Test
    fun lista_de_escalares_al_nivel_de_la_clave() {
        val y = """
            bloqueadas:
            - dinero
            - clave
            otro: 1
        """.trimIndent()
        val m = MiniYaml.parse(y).mapa()
        assertEquals(listOf<Any?>("dinero", "clave"), m["bloqueadas"])
        assertEquals(1, m["otro"])
    }

    @Test
    fun vacio_devuelve_null() {
        assertNull(MiniYaml.parse("   \n# solo comentarios\n"))
        assertTrue(MiniYaml.parse("a: 1").mapa().isNotEmpty())
    }
}
