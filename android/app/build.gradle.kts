import java.util.Properties

plugins { id("com.android.application") }

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val previewDirectory = providers.environmentVariable("TIPTOI_PREVIEW_SIGNING_DIR").orNull
    ?: local.getProperty("previewSigningDir")
val catalogClientFile = providers.environmentVariable("TIPTOI_CATALOG_CLIENT_FILE").orNull
    ?: local.getProperty("catalogClientFile")
val catalogClient = Properties().apply {
    catalogClientFile?.let { file(it).reader().use { reader -> load(reader) } }
}
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "io.github.kzahel.tiptoimanager"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "io.github.kzahel.tiptoimanager"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("TIPTOI_VERSION_CODE").orElse("1").get().toInt()
        versionName = "0.1.0-spike"
        testInstrumentationRunner = "io.github.kzahel.tiptoimanager.UsbSmokeInstrumentation"
        buildConfigField("String", "CATALOG_CLIENT_ID", quoted(catalogClient.getProperty("client.id", "")))
        buildConfigField("String", "CATALOG_CLIENT_SECRET", quoted(catalogClient.getProperty("client.secret", "")))
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.directories.add(rootProject.file("licenses").path)
}
dependencies {
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("org.jsoup:jsoup:1.23.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
