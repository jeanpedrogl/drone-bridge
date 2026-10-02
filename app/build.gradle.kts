import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Personal/sensitive values live in secrets.properties at the project root (gitignored).
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.jeanpedrogl.dronebridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.jeanpedrogl.dronebridge"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        multiDexEnabled = true

        manifestPlaceholders["DJI_API_KEY"] =
            secrets.getProperty("DJI_API_KEY", "REPLACE_WITH_YOUR_DJI_APP_KEY")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "**/*.so"
            )
        }
        resources {
            excludes += listOf(
                "META-INF/rxjava.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// DocsCoverageTest reads these files. Declared as inputs so Gradle reruns the unit tests when only
// the docs change, instead of skipping them as up-to-date.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.files("PROTOCOLO.md", "MANUAL_DESENVOLVEDOR.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    // The android.jar stubs of org.json throw on the JVM; the real implementation lets RpcRegistry be tested.
    testImplementation("org.json:json:20180813")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(libs.dji.sdk) {
        exclude(group = "com.squareup.okhttp3")
    }
    compileOnly(libs.dji.sdk.provided)

    // WebSocket client for the rosbridge connection to the computer (ponte ROS2).
    implementation(libs.okhttp)
}