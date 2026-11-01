import java.util.Properties

plugins { id("com.android.application") }

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val previewDirectory = providers.environmentVariable("TIPTOI_PREVIEW_SIGNING_DIR").orNull
    ?: local.getProperty("previewSigningDir")

android {
    namespace = "io.github.kzahel.tiptoimanager"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.kzahel.tiptoimanager"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("TIPTOI_VERSION_CODE").orElse("1").get().toInt()
        versionName = "0.1.0-spike"
    }
    if (previewDirectory != null) signingConfigs.create("preview") {
        storeFile = file("$previewDirectory/preview.jks")
        storePassword = file("$previewDirectory/password.secret").readText().trim()
        keyAlias = "preview"
        keyPassword = storePassword
    }
    buildTypes { getByName("debug") {
        applicationIdSuffix = ".debug"
        if (previewDirectory != null) signingConfig = signingConfigs.getByName("preview")
    } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
