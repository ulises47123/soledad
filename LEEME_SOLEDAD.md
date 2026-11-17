# Soledad

App Android (paquete `com.soledad.app`) que contesta **tu** WhatsApp cuando no estás, **solo** a los contactos de tu
lista blanca, con un agente de IA que usa tu propia información. Nace del proyecto
`agente-whatsapp-ia` (que no se modificó).

> Se instala **apagada**, con la **lista blanca vacía** y en **modo prueba**. No responde a nadie hasta que vos la
> enciendas, agregues contactos y apagues el modo prueba.

## Qué hace

1. **Reacciona apenas llega un mensaje** (ajuste «Responder apenas llega un mensaje», activado por defecto): la
   escucha de notificaciones programa la respuesta en el momento, también con el teléfono bloqueado y en reposo.
   Además **revisa a horarios al azar** como respaldo: cada vez sortea el próximo intervalo entre **20 min, 50 min o
   2 h**, con una variación al azar de ± unos minutos (por defecto ±5). Un mismo mensaje nunca se responde dos veces.
2. **Mira si hay mensajes nuevos** leyendo la bandeja de notificaciones de WhatsApp (no hace falta desbloquear).
   Si el teléfono está desbloqueado y en la pantalla de inicio, además **entra un momento a WhatsApp** (unos segundos,
   con Shizuku) y vuelve al inicio. Si estás usando otra app o el teléfono está bloqueado, **no entra**: solo lee la bandeja.
3. **Filtra:**
   - **a quién responde** (Ajustes): «Solo a mi lista blanca» (por defecto) o «A todos los que me escriban» (todos
     los chats individuales; pide confirmación con un aviso del riesgo). Inicio y Lista blanca muestran cuál está activa;
   - con «Solo a mi lista blanca», solo contactos de la **lista blanca**: el nombre agendado, que es el que WhatsApp muestra en la notificación. Se compara
     sin mayúsculas, acentos, emojis ni espacios de más («Mamá ❤️» = «mama»);
   - los **grupos nunca** se responden (aunque el nombre esté en la lista);
   - solo mensajes que llegaron **con Soledad encendida**;
   - no responde mensajes triviales («ok», stickers, audios…) ni los que tocan **temas sensibles**
     (plata, claves, códigos, banco, CBU/alias…) ni más de 4 veces por hora al mismo chat;
   - si la notificación desapareció antes de responder, entiende que **lo leíste vos** y no responde.
4. **Espera al azar entre 1 y 5 minutos** y responde con la IA:
   - **RAG** sobre «Mi información» (búsqueda por significado con embeddings de NVIDIA
     `nvidia/nemotron-3.5-embed-1b` + índice BM25 local del proyecto base);
   - modelo **NVIDIA NIM** `nvidia/nemotron-3.5-super-vl-120b-a12b` (sin «pensamiento»), con **Gemini** de respaldo;
   - **varias claves por proveedor**: prueba en orden NVIDIA 1, 2, … y después Gemini 1, 2, …; pasa a la siguiente
     ante falta de red, tiempo agotado, 401/403, 429, 5xx o respuesta vacía. El Registro dice qué alias redactó
     («redactó nvidia-2»), nunca la clave;
   - agrega **«(respuesta automática)»** al final (activado por defecto; se apaga en Ajustes).
5. **Envía** con el botón **«Responder» de la notificación** (funciona con el teléfono bloqueado) y confirma que salió
   (eco en la notificación). Si no hay botón «Responder» y el teléfono está bloqueado:
   - con **«Desbloquear el celular para responder»** (opt-in, **apagado por defecto**): teclea tu PIN o contraseña
     (guardado cifrado) por Shizuku, envía abriendo el chat y **vuelve a bloquear** (inicio + apagar pantalla). Lo
     mismo para «entrar un momento a WhatsApp» con el teléfono bloqueado. Si falla 3 veces, no insiste durante 1 hora y
     la respuesta queda **pendiente**;
   - con el ajuste apagado, **no desbloquea**: queda **pendiente** en el Registro y reintenta más tarde.
   Opcional (apagado por defecto): «Enviar abriendo el chat», que con el teléfono desbloqueado abre el chat por
   Shizuku y toca Enviar (método del proyecto base).

## Pantallas

| Pantalla | Para qué |
|---|---|
| **Inicio** | Interruptor «Responder por mí», estado (encendida/apagada, modo prueba), próxima revisión, último resultado, respuestas programadas, lo que falta configurar y «Revisar ahora». **Salir** está siempre arriba a la derecha. |
| **Lista blanca** | **Elegir de mis contactos**: abre tu agenda con buscador (por nombre o parte del número) y casillas; marcás varios y «Agregar marcados». Muestra «…1234» (últimos 4 dígitos) para distinguir homónimos y «ya te escribió por WhatsApp» si Soledad ya detectó mensajes de ese nombre. Alternativa secundaria: «Escribir un nombre a mano», para quien no está agendado o aparece en WhatsApp con otro nombre. |
| **Mi información** | Tu base de conocimiento por secciones: *Sobre mí*, *Gustos*, *Cómo contesto*, *Temas que no toco*. Empieza **vacía** (el texto gris es solo un ejemplo). «Probar con un mensaje» genera una respuesta sin enviarla. |
| **Registro** | Respuestas programadas y qué detectó, qué ignoró y qué respondió (o habría respondido en modo prueba). |
| **Ajustes** | A quién responde (lista blanca o todos), modo prueba, etiqueta «(respuesta automática)», responder apenas llega, intervalos (20/50/120), variación, espera 1–5 min, entrar a WhatsApp, enviar abriendo el chat, desbloqueo (opt-in, con PIN/contraseña cifrado) y **claves de IA**: varias de NVIDIA y de Gemini, agregar / cambiar / quitar / subir / bajar, estado de cada una (última vez que anduvo o último error) con solo los últimos 4 caracteres, y «Probar todas». |

**Salir** cierra todo: apaga Soledad, cancela las alarmas y las respuestas programadas, detiene el servicio, quita la
notificación persistente, desenlaza el acceso a notificaciones (para que Android no la vuelva a levantar), cierra la
pantalla y termina el proceso. Para volver a usarla: abrirla y encender el interruptor.

## Cómo activarla (paso a paso)

1. Abrí **Soledad**. En «Lo que necesita» todo debería estar en ✓ (acceso a notificaciones, clave de IA, Shizuku,
   batería, alarmas exactas, avisos). Si algo falta, tocá su botón.
2. **Mi información**: completá las secciones que quieras y tocá «Guardar mi información». No pongas claves,
   documentos, direcciones ni datos bancarios.
3. **Ajustes → Probar con un mensaje**: escribí algo como «Hola, ¿qué hacés el finde?» y mirá la respuesta. No se envía.
4. **Lista blanca → Elegir de mis contactos**: marcá **un** contacto de confianza y tocá «Agregar marcados».
   (Si negaste el permiso de contactos, la pantalla lo explica y podés escribir el nombre a mano.)
5. **Inicio**: encendé «Responder por mí». Con el **modo prueba** todavía activo, las respuestas solo quedan en el Registro.
6. Prueba real (guion): pedile a ese contacto que te escriba **desde otro teléfono**. Bloqueá el tuyo y no abras
   WhatsApp. En la próxima revisión (o tocando «Revisar ahora» antes de bloquear) Soledad lo detecta; en 1–5 min
   aparece en el Registro como «Prueba (no enviada)» con la respuesta que habría mandado.
7. Si te gusta cómo responde: **Ajustes → apagá Modo prueba**. Repetí el paso 6: ahora la respuesta llega de verdad
   con «(respuesta automática)» y el Registro dice «Respondió … (confirmado)».
8. Para dejar de usarla: apagá el interruptor o tocá **Salir**.

## Riesgos (leer antes de encender)

- **WhatsApp puede bloquear cuentas que automatizan respuestas.** Las condiciones de WhatsApp no permiten usos
  automatizados en cuentas personales. Soledad responde poco, con esperas al azar, solo a tu lista y por la vía normal
  de la notificación, pero **el riesgo no es cero**. Usala con pocos contactos de confianza.
- **Privacidad:** el texto de los mensajes de tu lista blanca y los fragmentos de «Mi información» que hagan falta se
  envían a **NVIDIA** (y a **Google/Gemini** si NVIDIA falla) para redactar cada respuesta. Los mensajes de gente fuera
  de la lista y de grupos **no** se envían a ninguna IA (solo se anota el nombre en el Registro).
- **Agenda:** Soledad lee tus contactos (permiso READ_CONTACTS) solo para mostrarte el selector. No guarda la agenda,
  no la registra y no la manda a la IA: solo guarda los nombres que marcás.
- La IA puede equivocarse o sonar rara. Por eso existe el modo prueba y la etiqueta «(respuesta automática)».
  Tiene órdenes de no inventar datos, no aceptar planes ni pagos y no hablar de los «Temas que no toco».
- Si WhatsApp cambia sus notificaciones, Soledad puede dejar de ver mensajes o de confirmar envíos (falla segura:
  no envía de más; una respuesta sin confirmar no se reintenta para no duplicarla).
- **«A todos los que me escriban»** contesta también a desconocidos y empresas: más riesgo de bloqueo de WhatsApp
  y más mensajes enviados a la IA. Los grupos nunca.
- **Desbloqueo (opt-in):** guarda tu PIN o contraseña del teléfono cifrado (Android Keystore) y lo teclea por
  Shizuku. Mientras responde, el teléfono queda desbloqueado unos segundos. La credencial nunca va a logs, al
  Registro, a capturas, a BuildConfig ni a local.properties. Si falla 3 veces no insiste (evita que el teléfono se trabe).
- HyperOS puede frenar apps en segundo plano: dejá la batería «sin restricciones» y Shizuku corriendo.

## Técnica (resumen)

- Reutiliza del proyecto base: cola SQLite (estados, dedupe, lease), lista de contactos (modo «permitidos»),
  RAG BM25, gestor de APIs con cuotas y claves cifradas en el Keystore, cliente Gemini, envío por «Responder» /
  Shizuku con verificación, registro SQLite, MiniYaml y la capa de interfaz.
- Nuevo: `soledad/` (motor de revisiones al azar, alarmas exactas, servicio en primer plano, Salir), `ClienteNvidia`
  (chat + embeddings), búsqueda semántica, «Mi información», pantallas e ícono propio (vector adaptable).
- Se quitó: comando/menú `-/4`. El desbloqueo volvió en la 1.2 como opción **opt-in** (adaptado de
  `app-tareas-automaticas/puente/Dispositivo.kt`): `soledad/Desbloqueo.kt`.
- Claves: en la compilación de prueba salen de `local.properties` (`NVIDIA_API_KEY`, `GEMINI_API_KEYS`, no se versiona)
  y se guardan cifradas al primer arranque. También se pueden cargar en Ajustes.

### Compilar, probar e instalar

```bash
source tools/env.sh                 # JDK y SDK de app-mando-facil/.tools
./gradlew testDebugUnitTest         # tests JVM
./gradlew assembleDebug             # app/build/outputs/apk/debug/Soledad-debug.apk
adb -s <serial> install -r app/build/outputs/apk/debug/Soledad-debug.apk
```

## Estado de la instalación (17/11/2026)

- Instalada en el Xiaomi como **Soledad 1.0** (versionCode 1), **apagada**, **lista blanca vacía**, **modo prueba activado**.
- Permisos concedidos: acceso a notificaciones, avisos, alarmas exactas, excluida del ahorro de batería, permiso de Shizuku.
- Prueba real hecha: «Probar con un mensaje» (texto sin datos personales) → respondió NVIDIA y quedó en el Registro como
  «Prueba (no enviada)». No se envió nada a nadie ni se abrió ningún chat.
- Capturas en `capturas/`.
- Ojo: en el teléfono sigue instalada la app vieja **Agente** (`com.agente.app`) con acceso a notificaciones. Si estaba
  encendida, también podría responder WhatsApp: conviene apagarla o desinstalarla para no tener dos bots.

## Versión 1.1 (17/11/2026)

- Lista blanca: se elige **desde los contactos del teléfono** (buscador + casillas, varios a la vez). La entrada a mano
  queda como alternativa. Permiso READ_CONTACTS concedido por adb.
- La comparación de nombres ignora mayúsculas, acentos, emojis, signos y espacios de más (`lista/Nombres.kt`).
- Inicio se refresca solo cuando termina «Revisar ahora» (antes «Último resultado» y «Próxima revisión» quedaban viejos).
- 151 tests JVM, 0 fallos.

## Versión 1.2 (17/11/2026)

- **Varias claves de IA con respaldo**: `NVIDIA_API_KEYS=a,b` en local.properties (se sigue aceptando la singular
  `NVIDIA_API_KEY`) y `GEMINI_API_KEYS=…`; cada clave se importa una sola vez al arrancar. En Ajustes: agregar, cambiar,
  quitar, reordenar, estado por clave (últimos 4 caracteres) y «Probar todas».
- **A quién responde**: «Solo a mi lista blanca» (por defecto) o «A todos los que me escriban» (con confirmación).
  Grupos nunca.
- **Responder apenas llega un mensaje** (por defecto activado), con la misma espera al azar de 1–5 min; las revisiones
  al azar quedan de respaldo y no se duplican respuestas.
- **Desbloquear el celular para responder** (opt-in, apagado por defecto): solo cuando no hay «Responder» en la
  notificación o para entrar un momento a WhatsApp; vuelve a bloquear al terminar; 3 fallos → pendiente.
- **Arreglo**: «Agregar marcados» en el selector de contactos solo agrega; quitar es exclusivo del botón «Quitar»
  (que además ignora toques durante un instante después de cerrar el selector).
- Los mensajes ignorados (fuera de la lista, grupos) se anotan una sola vez en el Registro.
