import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

base {
    archivesName.set("Soledad")
}

// Claves SOLO para la compilación de prueba: salen de local.properties (no se versiona) y van a BuildConfig.
// En el teléfono se guardan cifradas la primera vez; también se pueden cargar desde Ajustes.
val locales = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun local(nombre: String): String = (locales.getProperty(nombre) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.soledad.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.soledad.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        buildConfigField("String", "NVIDIA_API_KEY", "\"${local("NVIDIA_API_KEY")}\"")
        buildConfigField("String", "NVIDIA_API_KEYS", "\"${local("NVIDIA_API_KEYS")}\"")
        buildConfigField("String", "GEMINI_API_KEYS", "\"${local("GEMINI_API_KEYS")}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Las configuraciones viven en flujo_bot/ y gestor_apis/ (raíz del proyecto); se empaquetan como assets
// y la app las copia a su almacenamiento la primera vez, donde la interfaz las puede editar.
val sincronizarAssets = tasks.register<Copy>("sincronizarAssets") {
    from(rootProject.file("flujo_bot")) {
        include("**/*.yaml")
        into("flujo_bot")
    }
    from(rootProject.file("gestor_apis")) {
        include("config_apis.yaml")
        into("gestor_apis")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(sincronizarAssets) }

dependencies {
    // Capa de privilegios (Shizuku): entrar un momento a WhatsApp, appops, uiautomator.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // El org.json de android.jar es un stub en la JVM de los tests.
    testImplementation("org.json:json:20231013")
}
