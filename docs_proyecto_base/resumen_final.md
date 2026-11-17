# Resumen final — Agente v2 (reconstrucción)

APK compilado: `app/build/outputs/apk/debug/Agente-debug.apk` (3,3 MB). **No está instalado en el celular** (el que hay instalado es el v1).
Código del v1 conservado, sin tocar, en `C:\Users\Usuario\Music\AgenteCelu_v1`.

## Qué se hizo, por fases
| Fase | Resultado |
|---|---|
| 0 Auditoría | `00_auditoria.md`. Defectos demostrados en el código: envío dado por bueno sin verificar; cola solo en memoria; el bot parecía muerto con la configuración por defecto (retorno silencioso sin registro). La causa real **no** se pudo confirmar en el dispositivo (estaba inalcanzable). |
| 1 Planteamiento | `01_planteamiento.md`: dos caminos, un único paso con modelo, Shizuku, un gestor de APIs, menú determinista, cola SQLite (equivalente a Redis+BullMQ en Android). |
| 2–3 Subtareas y briefs | 6 subtareas con dueño único + 1 de estilos, cada una con brief de 5 partes y lock-file. |
| 5 Matriz | `02_matriz.md`: sin temas ni carpetas repetidos. |
| 6–6B Flujo y menú | Implementados (ver tests). |
| 7 Shizuku | `skills/automatizacion_movil/` (`grabaciones/` vacía y de solo lectura, `nuevas/`). |
| 8 Estilos | Skill **`better-interface`** (`skills/estilos/SKILL.md`). El subagente la cargó y citó ~30 reglas (`tareas/estilos/estilos.md`). |
| 10 Gestor de APIs | 3 usos por clave, bloqueo hasta el reinicio de la ventana (`24h`/`diario_utc`), estado transaccional en `estado_cuotas.json`. |

## Estructura
`flujo_bot/` (config de comando, menú, lista y RAG), `gestor_apis/`, `tareas/` (un directorio por subtarea, con su informe), `equipos/envio_verificado/COORDINACION.md`, `skills/`, y el código en `app/src/main/kotlin/com/agente/app/{nucleo,recepcion,comando,lista,rag,apis,envio,ui}`.

## Verificado (reproducido por el coordinador en el proyecto unificado)
**144 tests unitarios JVM, 0 fallos**: lector YAML 5, cola/orquestador 17, comando y menú 8, lista 12, gestor de APIs 21, RAG 16, envío/verificación/Shizuku 43, interfaz 22.
Demuestran, con piezas falsas: el flujo de 7 pasos en orden; el camino de comando sin tocar IA/APIs/RAG; imagen aleatoria distinta entre ejecuciones y siempre dentro de la carpeta declarada; enlace exacto; opción nueva solo en YAML aparece en el menú; permiso del gestor pedido antes de llamar al modelo; 3 usos y bloqueo; reembolso; concurrencia sin exceder el límite; escritura atómica; `VERIFICADO` solo con verificación confirmada; un `FALLIDO` no bloquea al siguiente; `liberarCiclo` siempre; el PIN y las claves nunca aparecen en registros ni en la interfaz.

## NO verificado (honesto)
Las condiciones de la Fase 9 que exigen dispositivo **no se cumplieron**:
- Ciclo completo con el celular bloqueado al inicio, desbloqueo, envío y verificación real.
- Cualquier contacto con WhatsApp real y cualquier llamada real a Gemini.
- SQLite real, el servicio 24/7 en Android 15, Shizuku, Keystore, la lectura de assets y todo lo visual (nada se vio en pantalla).
- Los ids y descripciones de estado de WhatsApp que usa la verificación por interfaz, y la forma de sus notificaciones: **los volcados de los tests los fabricó el propio subagente; son suposiciones**.
- Que la cola sobreviva a un reinicio *real* del proceso (solo con almacén en memoria).
Guion para validar todo con el propio número del usuario: `tareas/envio_verificado/guion_prueba_dispositivo.md`.

## Decisiones tomadas por el coordinador (revisables)
1. **Lista:** `modo: excluidos` por defecto (lectura literal de las instrucciones: quien no está en la lista va a la IA). Cambiable a `permitidos`. **Pendiente de confirmar con el usuario.**
2. **Reintentos de envío: 1** (el subagente dejó 3). Con 3, un mensaje que sí salió pero no se verificó se enviaba repetido al contacto. En `ConfigOrquestador.intentosEnvio`.
3. **Interruptor general apagado, grupos sin respuesta, etiqueta «(respuesta automática)» activa, carpeta de imágenes vacía** hasta que el usuario las configure.

## Riesgos que conviene conocer
- Un mensaje que queda `ENVIADO` si el proceso muere antes de verificar **no se reintenta** (a propósito, para no duplicar); no se sabría si salió.
- El texto de los mensajes y los fragmentos recuperados se envían a Google (Gemini); la lista y el modo limitan a quién.
- La verificación de envío depende de cómo muestra WhatsApp sus notificaciones e interfaz; si cambia, `verificar` devolverá falso y el mensaje quedará `FALLIDO` (falla seguro, no envía de más).
