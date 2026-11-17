# Soledad

App Android (Kotlin, sin dependencias de UI externas) que **contesta tu WhatsApp con IA cuando no estás**,
solo a los contactos de una lista blanca (o, si lo elegís, a todos los chats individuales; nunca a grupos) y usando la información que vos cargues («Mi información», con RAG).
Usa NVIDIA NIM como modelo principal y Gemini como respaldo, con varias claves por proveedor probadas en orden. Se instala **apagada**, con la lista vacía y en
**modo prueba** (genera respuestas pero no las envía).

La documentación completa (funcionamiento, pantallas, riesgos y guía paso a paso) está en
[`LEEME_SOLEDAD.md`](LEEME_SOLEDAD.md). Capturas en [`capturas/`](capturas/).

## Requisitos

- Android 11+ (minSdk 30), [Shizuku](https://shizuku.rikka.app/) en ejecución.
- JDK 17 y Android SDK (compileSdk 37).
- Una clave de NVIDIA NIM y/o de Gemini (propias).

## Claves de API

Este repositorio **no incluye ninguna clave**. Hay dos formas de cargarlas:

1. **Desde la app**: *Ajustes → Claves de IA* (se guardan cifradas con el Android Keystore y nunca se muestran).
2. **Al compilar**: copiá `local.properties.example` como `local.properties` (está en `.gitignore`) y completá
   `NVIDIA_API_KEYS` (una o varias, separadas por coma; también vale `NVIDIA_API_KEY`) y `GEMINI_API_KEYS`.
   La app importa cada clave cifrada una sola vez al arrancar.

## Compilar y probar

```bash
./gradlew testDebugUnitTest   # tests JVM
./gradlew assembleDebug       # app/build/outputs/apk/debug/Soledad-debug.apk
adb install -r app/build/outputs/apk/debug/Soledad-debug.apk
```

## Aviso

Las condiciones de WhatsApp no permiten automatizar cuentas personales: usala con pocos contactos de confianza y
bajo tu responsabilidad. Los mensajes de la lista blanca se envían a NVIDIA/Google para redactar cada respuesta.
