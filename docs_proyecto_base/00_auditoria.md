# 00 — Auditoría del proyecto actual (Agente v1)

Código auditado: `C:\Users\Usuario\Music\AgenteCelu_v1` (copia exacta del proyecto anterior, 18 archivos Kotlin, 1 653 líneas, un solo paquete plano `com.agente.app`).
Método: lectura completa del flujo recepción → decisión → envío (`Escucha`, `Cerebro`, `Enviador`, `Receptor`, `Accesibilidad`) y de la estructura general.

> **Límite de esta auditoría.** El celular no respondía al hacer la auditoría (sin ping, sin adb), así que **no se pudo leer el estado real en el dispositivo** (registro, permisos, procesos). Además, el agente v1 **nunca se probó contra WhatsApp real**: se compiló y se instaló, nada más. Lo que sigue son defectos demostrables **en el código** y sospechosos ordenados por probabilidad, no una causa confirmada en ejecución.

## 1. Módulos existentes y responsabilidad real

| Módulo | Dice hacer | Hace en realidad |
|---|---|---|
| `Escucha` | Leer notificaciones de WhatsApp | Lee, deduplica con un mapa **en memoria**, guarda la acción «Responder» en `Enviador`, **anota contactos en la BD** y lanza el procesamiento. |
| `Cerebro` | Decidir si responder | Hace **todo**: interruptores, horario, lista blanca, filtros, sensibles, límites, RAG, caché, llamada a Gemini, aprobar/enviar directo y registro. Es el cuello de botella y concentra 8+ responsabilidades. |
| `Enviador` | Enviar | Guarda en memoria las acciones de respuesta, envía por `RemoteInput`, detecta mensajes propios por ventana de 4 s y contiene el **respaldo por accesibilidad**. |
| `Receptor` | Botones de la propuesta | Segundo camino hacia `Enviador` (aprobar/editar/descartar). |
| `Avisos` | Notificaciones al usuario | Propuestas y avisos de error. |
| `Gemini`, `Indice`, `Db`, `Cfg`, `Secreto`, `Priv` | Cliente, BM25, SQLite, ajustes, Keystore, Shizuku | Aceptables como piezas aisladas. |
| `Accesibilidad` | Respaldo de escritura | Escribe y toca «Enviar»; **no desbloquea** ni verifica nada. |

## 2. Solapamientos y escrituras compartidas

- `Cerebro.entrante()` lanza `exec.execute { procesar }` **dentro** de un `exec.execute { … }` que ya puso `Escucha` en el mismo ejecutor de un solo hilo: doble encolado sin motivo.
- `Db` es un singleton escrito desde `Escucha` (contactos), `Cerebro` (registro, uso, caché, propuestas) y `Receptor` (registro, propuestas). Tres escritores sin coordinación.
- El estado de envío (`hilos`, `ultimoEnvio`, `enviados`) vive en `Enviador` como mapas en memoria que también consulta `Escucha` (`esPropio`). Dos módulos dependen del mismo estado volátil.
- El orden de comprobaciones está **dentro** de `Cerebro.procesar` mezclado con efectos (registrar, avisar), y no en un flujo declarado.

## 3. Causa raíz del fallo actual (por orden de probabilidad)

| # | Hallazgo | Evidencia en el código | Estado |
|---|---|---|---|
| 1 | **El bot parece muerto aunque funcione.** Con la configuración por defecto (nadie en la lista blanca) `Cerebro.procesar` hace `return` en silencio en el paso 1, **sin registrar nada**. Para el usuario es indistinguible de un fallo. | `Cerebro.kt:71` (`if (c == null \|\| !c.activo) return`) | Demostrado en código |
| 2 | **El envío nunca se verifica.** `Enviador.enviar` devuelve `ok = true` en cuanto `actionIntent.send()` no lanza excepción, y `Cerebro` lo registra como `ENVIADA`. No hay confirmación de que el mensaje saliera. | `Enviador.kt:48-50` | Demostrado en código |
| 3 | **Se pierde el mensaje si el proceso muere.** La «cola» es un `newSingleThreadExecutor` en memoria; las acciones de respuesta y el mapa `ultimo` también. Tras un reinicio se pierde lo pendiente y la ventana de 90 s puede duplicar o saltarse mensajes. | `Cerebro.kt:15`, `Escucha.kt:14,36` | Demostrado en código |
| 4 | **Depende de que exista una notificación con «Responder».** Si el usuario ya leyó el chat o descartó la notificación, `hilos[clave]` no existe y solo queda el respaldo (apagado por defecto). | `Enviador.kt:42` | Demostrado en código |
| 5 | **El dispositivo bloqueado detiene el ciclo.** El respaldo exige pantalla encendida y desbloqueada; solo sabe despertar la pantalla, no desbloquear. | `Enviador.kt:74-75` | Demostrado en código |
| 6 | **Supuestos sobre la notificación de WhatsApp sin verificar** (`EXTRA_MESSAGES`, `shortcutId`, detección de grupo, ids `entry`/`send`). | `Escucha.kt:24-33`, `Accesibilidad.kt:28,32` | Sin verificar |
| 7 | Falta la API key o el servicio de acceso a notificaciones sin activar. | `Cerebro.kt:90` (sí avisa), listener sin comprobación visible | Sin verificar |

**No existía** control de APIs por clave con cuotas (solo un tope diario de tokens), ni detección de comando, ni menú: no hay trabajo descoordinado que unificar, hay que crearlo.
**La semántica de «lista»** de v1 (lista blanca: solo responde a los activos) es la **contraria** a una lectura literal de las instrucciones (no está en la lista → IA). Se resuelve en `01_planteamiento.md`.

## 4. Rescatable y a reescribir

| Pieza | Decisión | Motivo |
|---|---|---|
| `Indice.kt` (BM25 local, troceo) | **Rescatar** (copiar a `integracion_rag`) | Funciona aislada, sin dependencias de red. |
| `Gemini.kt` (cliente REST) | **Rescatar y envolver** con el gestor de APIs | Es válido, pero hoy lee la clave directo de `Cfg`. |
| `Secreto.kt` (Keystore) | **Rescatar** | Cifrado de claves. |
| `Priv.kt` (Shizuku por reflexión) | **Reescribir** sobre el patrón de `automatic` | Solo despierta la pantalla; hace falta desbloqueo, `input`, `uiautomator`. |
| `Escucha.kt` | **Reescribir** | Debe solo recibir y encolar; hoy decide y escribe contactos. |
| `Cerebro.kt` | **Reescribir por completo** | Dividir en orquestador + detección de comando + lista + RAG/IA. |
| `Enviador.kt`, `Receptor.kt`, `Accesibilidad.kt` | **Reescribir** | Separar envío de verificación; estado persistente. |
| `Db.kt` | **Reescribir** con una tabla `cola` y estados | Hoy no hay cola persistente. |
| `*Activity.kt`, `Ui.kt`, `Avisos.kt` | **Reutilizar la idea, rehacer la UI** al final (estilos) | La interfaz debe reflejar las nuevas piezas (menú, APIs, cola). |

## 5. Conclusión

El v1 es una cadena lineal frágil: el mensaje solo vive en memoria, la decisión está concentrada en un objeto y el envío se da por bueno sin comprobar. **No se parchea.** Se reconstruye con cola persistente, camino de comando separado, gestor de APIs único y verificación de envío obligatoria (ver `01_planteamiento.md`).
