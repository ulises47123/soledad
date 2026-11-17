package com.soledad.app.envio

import java.util.Calendar
import java.util.TimeZone

class RelojFalso(var t: Long = 1_700_000_000_000L) : Reloj {
    override fun ahora() = t
    override fun dormir(ms: Long) { t += ms }
}

/** Shell falso: registra (comando, sensible) y responde con la función dada. */
class FalsoShell(var disp: Boolean = true, val resp: (String) -> ResultadoShell = { ResultadoShell(0, "") }) : EjecutorShell {
    val comandos = ArrayList<Pair<String, Boolean>>()
    override fun disponible() = disp
    override fun ejecutar(comando: String, limiteMs: Long, sensible: Boolean): ResultadoShell {
        comandos.add(comando to sensible)
        return resp(comando)
    }
    fun texto() = comandos.map { it.first }
    fun hubo(parte: String) = comandos.any { it.first.contains(parte) }
}

fun ok(s: String = "") = ResultadoShell(0, s)

/** [l,t,r,b] -> formato real de uiautomator [l,t][r,b]. */
private fun bu(b: String) = b.trim('[', ']').split(',').let { "[${it[0]},${it[1]}][${it[2]},${it[3]}]" }

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "&#10;")

fun hoja(id: String = "", text: String = "", desc: String = "", b: String = "[0,0,100,50]", clicable: Boolean = false) =
    """<node index="0" text="${esc(text)}" resource-id="$id" class="android.view.View" content-desc="${esc(desc)}" clickable="$clicable" bounds="${bu(b)}" />"""

fun caja(hijos: String, id: String = "") =
    """<node index="0" text="" resource-id="$id" class="android.view.ViewGroup" content-desc="" clickable="false" bounds="[0,0][1080,2400]">$hijos</node>"""

fun volcado(hijos: String) = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">$hijos</hierarchy>"""

/** Una burbuja propia: texto + hora + icono de estado, dentro de su fila. `desc` null = sin icono de estado. */
fun fila(texto: String, hora: String, estadoDesc: String?, arriba: Int = 1000): String = caja(
    hoja("com.whatsapp:id/message_text", texto, b = "[100,$arriba,900,${arriba + 80}]") +
        hoja("com.whatsapp:id/date", hora, b = "[800,${arriba + 80},900,${arriba + 110}]") +
        (if (estadoDesc != null) hoja("com.whatsapp:id/status", desc = estadoDesc, b = "[900,${arriba + 80},940,${arriba + 110}]") else ""),
    "com.whatsapp:id/main_layout",
)

fun hhmm(ms: Long, zona: TimeZone): String {
    val c = Calendar.getInstance(zona).apply { timeInMillis = ms }
    return "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
