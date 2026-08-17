// Importer explicitement : dans un script .gradle.kts, l'identifiant `java`
// désigne l'extension Gradle du plugin Java et masque le package `java.*`,
// donc `java.io.File` / `java.net.URI` ne compilent pas.
import java.io.File
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

// Modèle Whisper multilingue embarqué dans l'APK. Il pèse ~264 Mo, au-dessus
// de la limite de 100 Mo par fichier de GitHub, donc il est téléchargé au
// moment du build plutôt que versionné. Le nom vient de la liste officielle
// de whisper.cpp (models/download-ggml-model.sh).
val whisperModel = "small-q8_0"
val whisperModelFile = file("src/main/assets/models/whisper/ggml-$whisperModel.bin")

val downloadWhisperModel by tasks.registering {
    description = "Télécharge le modèle Whisper multilingue s'il est absent."
    outputs.file(whisperModelFile)
    doLast {
        if (whisperModelFile.exists() && whisperModelFile.length() > 50_000_000L) {
            logger.lifecycle("Modèle Whisper déjà présent : ${whisperModelFile.name}")
            return@doLast
        }
        whisperModelFile.parentFile.mkdirs()
        val url =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$whisperModel.bin"
        logger.lifecycle("Téléchargement du modèle Whisper $whisperModel…")
        val temp = File("${whisperModelFile.path}.part")
        temp.delete()
        URI(url).toURL().openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        // Une page d'erreur HTML ne ferait que quelques kilo-octets : refuser
        // un fichier manifestement trop petit plutôt que livrer un APK cassé.
        if (temp.length() < 50_000_000L) {
            val size = temp.length()
            temp.delete()
            throw GradleException("Modèle Whisper invalide ($size octets) depuis $url")
        }
        temp.renameTo(whisperModelFile)
        logger.lifecycle("Modèle téléchargé : ${whisperModelFile.length() / 1_000_000} Mo")
    }
}

tasks.named("preBuild") {
    dependsOn(downloadWhisperModel)
}

android {
    namespace = "dev.soupslurpr.transcribro"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.soupslurpr.transcribro"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = versionCode.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {

    implementation(project(":lib"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.activity:activity-ktx:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2025.07.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}