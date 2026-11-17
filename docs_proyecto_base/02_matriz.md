# 02 — Matriz anti-duplicación

Regla: si dos filas comparten tema o carpeta de salida, no se ejecuta; se reagrupa en un equipo con coordinador. **Resultado de la revisión: ningún tema ni carpeta se repite.**

| Subagente / Equipo | Tema | Carpeta de salida (única) | Archivo que produce | Criterio de verificación |
|---|---|---|---|---|
| **recepcion_cola_orquestador** | Recepción de notificaciones, cola persistente, ciclo de 7 pasos, servicio 24/7, registro | `tareas/recepcion_cola_orquestador/` | `recepcion_cola_orquestador.md` + `src/` | Tests: dedupe, persistencia entre reinicios del worker, lease vencido, orden de los 7 pasos con piezas falsas, la salida se bloquea hasta verificar |
| **comando_menu** | Detección del comando y menú sin IA | `tareas/comando_menu/` | `comando_menu.md` + `src/` + `flujo_bot/…/config.yaml` y `menu.yaml` | Tests: el comando activa el menú sin tocar IA/APIs, enlace exacto, aleatoriedad verificable, opción nueva en YAML aparece sin código |
| **verificacion_lista** | Interruptor, horario, grupos, modo de lista, sensibles, límite por hora | `tareas/verificacion_lista/` | `verificacion_lista.md` + `src/` + `flujo_bot/verificacion_lista/config.yaml` | Tests: ambos modos de lista, grupos, horario, sensibles, límite por hora |
| **gestor_apis** | Cuotas por clave (3 usos), persistencia transaccional, cliente del modelo | `tareas/gestor_apis/` | `gestor_apis.md` + `src/` + `gestor_apis/config_apis.yaml` y `estado_cuotas.json` | Tests: 3 usos y bloqueo, reinicio por ventana, reembolso, concurrencia, archivo atómico |
| **integracion_rag** | BM25 local, base de conocimiento, prompt, caché, `IaConRag` | `tareas/integracion_rag/` | `integracion_rag.md` + `src/` + `flujo_bot/integracion_rag/config.yaml` | Tests: pide permiso al gestor **antes** de llamar al modelo; sin llamada con caché o sin base |
| **Equipo envio_verificado** (coordinador único) | Envío por WhatsApp + verificación de envío + capa Shizuku (desbloqueo, input, uiautomator) | `tareas/envio_verificado/` | `envio_verificado.md` + `src/` + `skills/automatizacion_movil/…` + `equipos/envio_verificado/COORDINACION.md` | Compila; lógica pura con tests; guion de prueba en dispositivo (no ejecutable sin el celular) |
| **estilos** *(después de los demás)* | Interfaz completa | `tareas/estilos/` | `estilos.md` + `src/` | Carga `skills/estilos/SKILL.md` y la cita; compila con todo integrado |

**Por qué `envio_verificado` es un equipo:** enviar, verificar y operar el dispositivo comparten el mismo estado (qué se envió, a qué hora, en qué pantalla está WhatsApp). Si fueran tres dueños distintos, dos tocarían la misma cadena. Un coordinador único es dueño de las tres carpetas lógicas (`envio_whatsapp`, `verificacion_envio`, `automatizacion_movil`) y deja el reparto escrito en `equipos/envio_verificado/COORDINACION.md`.

**Lock-files:** cada carpeta de `tareas/` tiene un `.lock` mientras su dueño trabaja; al terminar lo reemplaza por `.done`. Quien encuentre un `.lock` ajeno no escribe en esa carpeta.
