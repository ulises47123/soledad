# 01 — Planteamiento y arquitectura

## 1. Principios
- **Un mensaje, un ciclo, una salida.** Ningún mensaje sale del ciclo sin que el envío esté verificado o el fallo registrado.
- **Workflow determinista salvo un único paso con modelo.** Solo la redacción con IA (paso 4 del camino normal) usa un modelo. Todo lo demás —recepción, cola, detección de comando, menú, lista, cuotas, envío, verificación, registro— es código determinista.
- **Un único dueño por pieza** (ver `02_matriz.md`) y un único gestor de APIs.
- **Adaptación a Android** de lo pedido en las instrucciones maestras (las equivalencias están en la sección 6).

## 2. Flujo completo (dos caminos)

```
 WhatsApp (notificación)
        │
 [1 recepcion] ──► COLA PERSISTENTE (SQLite, estados, dedupe, lease)
        │
 [Orquestador toma 1 mensaje]  ── prepara el dispositivo (Shizuku: encender/desbloquear)
        │
 [2 deteccion_comando] ── texto normalizado == comando configurado (por defecto "-/4")?
   │ SÍ → CAMINO DE COMANDO (sin IA, sin RAG, sin gestor de APIs)         │ NO
   │                                                                       ▼
   │  [menu_comandos] genera el menú numerado         [3 verificacion_lista] ¿corresponde responder?
   │  y lo envía al MISMO contacto                          │ NO → registra y cierra (sin respuesta)
   │  espera la elección (timeout configurable)             │ SÍ
   │  ejecuta la acción: imagen_aleatoria | enlace | texto  ▼
   │  (cada acción pasa por verificación de envío)    [4 integracion_rag] BM25 local → top-k fragmentos
   │                                                        │  pide permiso al [gestor_apis] (cuota)
   │                                                        ▼  llama al modelo (único paso con IA)
   └───────────────┬───────────────────────────────────────┘
                   ▼
          [5 envio_whatsapp]  (Shizuku + app de WhatsApp / acción «Responder»)
                   ▼
          [6 verificacion_envio]  confirma que el mensaje SALIÓ (timeout; reintenta o escala sin bloquear la cola)
                   ▼
          [7 registro y salida]  log con marca de tiempo + id; marca VERIFICADO; libera el dispositivo
```

**Orden no negociable:** el ciclo no termina hasta que `verificacion_envio` confirma, o se agota el reintento y el mensaje queda `FALLIDO` con motivo (nunca `ENVIADO` a ciegas, que era el defecto #2 de v1).

## 3. Workflow vs agente

| Paso | Tipo | Nota |
|---|---|---|
| Recepción, cola, detección de comando, menú, lista, cuotas, envío, verificación, registro | **Workflow determinista** | Sin modelo. Pruebas repetibles. |
| Redacción de la respuesta | **Agente (una llamada al modelo)** | Solo en el camino normal, solo si el gestor de APIs concede permiso. |

## 4. Decisiones de arquitectura

1. **Shizuku como capa de privilegios.** Siempre activo, da identidad de shell: `input`, `am`, `wm`, `uiautomator`, `content`, `dumpsys deviceidle`. Se encapsula en `skills/automatizacion_movil` (patrón del proyecto `automatic`). El bot no pide root.
2. **Un único gestor de APIs centralizado** con persistencia en **archivo JSON transaccional** (`estado_cuotas.json`: escritura a archivo temporal + `rename` atómico + bloqueo por proceso). Ningún otro módulo cuenta usos. Cuota por clave: 3 usos, bloqueo hasta la ventana de reinicio (`24h` o `diario_utc`).
3. **Manejador de comandos determinista** (`menu_comandos`): lee `menu.yaml`, no importa ni llama a la IA ni al gestor de APIs. Se añaden opciones editando el YAML.
4. **Cola persistente propia.** SQLite con estados `RECIBIDO → EN_PROCESO → ENVIADO → VERIFICADO | FALLIDO | DESCARTADO`, identificador de mensaje con dedupe, **lease** (un mensaje EN_PROCESO con lease vencido vuelve a RECIBIDO si el worker murió).
5. **Operación 24/7.** Servicio en primer plano que corre el worker, `NotificationListenerService` (el sistema lo vuelve a enlazar), `BOOT_COMPLETED`, exclusión de optimización de batería concedida vía Shizuku.

## 5. Decisiones que dependen del usuario (con valor por defecto)

| Tema | Valor por defecto | Motivo |
|---|---|---|
| **Qué significa «la lista»** | `modo: excluidos` (en `verificacion_lista/config.yaml`): la gente de la lista **no** recibe respuesta automática (la atiendes tú); el resto → IA. Se puede cambiar a `modo: permitidos`. | Es la lectura literal de la instrucción («si no está en la lista, a la IA»). v1 hacía lo contrario. **Pendiente de confirmar con el usuario.** |
| Interruptor general | **Apagado** hasta que el usuario lo encienda | Evita respuestas por sorpresa tras instalar. |
| Grupos | No se responde | Menos ruido y riesgo. |
| Temas sensibles (dinero, claves, códigos, datos bancarios) | No se responde y se avisa | Se conserva de v1. |
| Etiqueta «(respuesta automática)» | Activada, editable | Transparencia con quien recibe. |
| Aprobación previa | Opcional por configuración (camino normal) | El comando/menú es determinista y no la usa. |
| Comando especial | `-/4` en `deteccion_comando/config.yaml`, solo contactos individuales | Configurable sin tocar código. |
| Carpeta de `imagen_aleatoria` | **Vacía hasta que el usuario declare una** en `menu.yaml` | La selección no puede salir de la carpeta declarada. |

## 6. Equivalencias con las instrucciones maestras (entorno Android)

| Instrucción | Aquí |
|---|---|
| Redis + BullMQ | Cola SQLite persistente con estados, dedupe y lease (equivalente permitido). |
| Webhook de estado / API oficial | No se usa API oficial: la verificación es **por notificación** (eco del texto propio) y **por interfaz** (`uiautomator dump` vía Shizuku), con timeout. |
| `cuotas.py / cuotas.js` | `cuotas.kt` dentro de `app` (módulo `apis`) + los archivos de `gestor_apis/` como configuración y estado. |
| `config_apis.yaml` con `${OPENAI_API_KEY_1}` | Mismo formato; las claves se guardan **cifradas** en el dispositivo y el YAML referencia su nombre. El proveedor por defecto es Gemini (OpenAI como ejemplo del formato). |
| Estructura `proyecto/…` | Carpetas de configuración y documentación en la raíz de `AgenteCelu`, y el código en `app/src/main/kotlin/com/agente/app/<paquete>` con el mismo nombre que cada carpeta del flujo. |

## 7. Skill de estilos
**Skill elegida: `better-interface`** (de `C:\Users\Usuario\Desktop\Skills_GitHub\better-interface`; coordina `better-ui`, `better-layout`, `better-typography`, `better-colors`, `better-writing` y `better-accessibility`). Su `SKILL.md` está copiado en `skills/estilos/SKILL.md`. El subagente de estilos **debe cargarla y citarla** antes de entregar. No hizo falta descargar nada de internet.

## 8. Verificación de envío (diseño)
- **Estrategia A — notificación:** tras el envío, el listener espera el repintado de la notificación del chat y busca el texto enviado (autor «Tú»/sin remitente). Rápida, no siempre disponible.
- **Estrategia B — interfaz:** con el chat abierto, `uiautomator dump` vía Shizuku; se busca el nodo con el texto enviado y su estado (enviado/entregado/leído por su descripción). Más lenta, más fiable.
- Se intenta A y, si no hay confirmación en su plazo, B. Si ninguna confirma en el timeout total: reintento limitado y, si sigue sin confirmar, `FALLIDO` + aviso al usuario (sin bloquear la cola).
- **Riesgo reconocido:** ambos mecanismos dependen de cómo muestra WhatsApp sus notificaciones e interfaz; no se pueden validar sin el dispositivo.
