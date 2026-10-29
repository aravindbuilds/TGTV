import java.io.File
import java.util.Properties
import java.security.SecureRandom

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tgtv.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tgtv.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // ABI splits: one installable-per-device APK per ABI + a universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        // v1.0 ships signed with the standard debug certificate (same key the
        // first release APKs were signed with), so new builds install over
        // existing installs without uninstalling.
        create("release") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/bakedApi"))
        }
    }
}

// ---------------------------------------------------------------------------
// BakedApi: TDLib api_id/api_hash are read at build time from an untracked
// secrets file (never part of the repo, never greppable in source) and compiled
// into a Kotlin object as XOR-obfuscated byte arrays. When the file is absent a
// stub is generated so the project still builds and login falls back to the
// manual API-entry screen.
// Locations checked, in order:
//   1. <project>/secrets.properties        (gitignore this if you use it)
//   2. <parent>/tgtv-secrets.properties    (shared by TV + phone apps)
// Keys: api_id (int), api_hash (hex string)
// This raises the bar against string-scanning tools; it is not a defence
// against a determined reverse engineer. No client signature can be.
// ---------------------------------------------------------------------------
val bakedApiDir = layout.buildDirectory.dir("generated/bakedApi").get().asFile

val generateBakedApi by tasks.registering {
    val secrets = listOf(
        rootProject.file("secrets.properties"),
        rootProject.projectDir.parentFile?.resolve("tgtv-secrets.properties"),
        File("C:\\aravind\\Projects\\tgtv-secrets.properties"),
    ).firstOrNull { it?.isFile == true }

    fun prop(name: String): String =
        secrets?.let {
            val p = Properties(); it.inputStream().use { s -> p.load(s) }
            p.getProperty(name)?.trim().orEmpty()
        }.orEmpty()

    val rawId = prop("api_id")
    val rawHash = prop("api_hash")
    val rawSubs = prop("open_subtitle_api")
    outputs.upToDateWhen { false }
    inputs.property("apiDigest", "${rawId.hashCode()}:${rawHash.hashCode()}:${rawSubs.hashCode()}")
    doLast {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fun obfuscate(s: String): String {
            val src = s.toByteArray(Charsets.UTF_8)
            return src.indices.joinToString(",") { i ->
                "%d".format(src[i].toInt() xor key[i % key.size].toInt())
            }
        }
        val idBytes = obfuscate(rawId)
        val hashBytes = obfuscate(rawHash)
        val subsBytes = obfuscate(rawSubs)
        val keyBytes = key.joinToString(",") { b -> "%d".format(b.toInt()) }
        val kt = """
            package com.tgtv.mobile.baked

            object BakedApi {
                private val ID = intArrayOf($idBytes)
                private val HASH = intArrayOf($hashBytes)
                private val OPENSUBS = intArrayOf($subsBytes)
                private val KEY = byteArrayOf($keyBytes)

                private fun decode(src: IntArray): String {
                    val out = ByteArray(src.size)
                    for (i in src.indices) out[i] = (src[i] xor KEY[i % KEY.size].toInt()).toByte()
                    return String(out, Charsets.UTF_8)
                }

                val present: Boolean
                    get() = apiId() > 0 && apiHash().isNotBlank()

                fun apiId(): Int = decode(ID).trim().toIntOrNull() ?: 0

                fun apiHash(): String = decode(HASH)

                /** OpenSubtitles.com API key; empty when not baked. */
                fun openSubtitles(): String = decode(OPENSUBS)
            }
        """.trimIndent()
        val pkgDir = bakedApiDir.resolve("com/tgtv/mobile/baked")
        pkgDir.mkdirs()
        pkgDir.resolve("BakedApi.kt").writeText(kt, Charsets.UTF_8)
    }
}

tasks.named("preBuild") { dependsOn(generateBakedApi) }

dependencies {
    implementation("io.github.tdlib-android:core:0.1.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
