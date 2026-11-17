#!/usr/bin/env bash
# Variables del entorno de desarrollo. Uso: source tools/env.sh
# Usa .tools/ de este proyecto si existe; si no, reutiliza el JDK y el SDK del proyecto "mando".
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERRAMIENTAS="$RAIZ/.tools"
[ -d "$HERRAMIENTAS/jdk" ] || HERRAMIENTAS="$RAIZ/../app-mando-facil/.tools"
export JAVA_HOME="$HERRAMIENTAS/jdk"
export ANDROID_HOME="$HERRAMIENTAS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
