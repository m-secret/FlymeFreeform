plugins {
    // AGP 9 自带 Kotlin 支持，本模块刻意不声明 org.jetbrains.kotlin.android。
    // 额外声明会让 Gradle 认为插件已在 classpath 且版本未知，直接报错。
    alias(libs.plugins.android.application)
}

val flymeFreeformVersionCode = providers.gradleProperty("flymeFreeformVersionCode").get().toInt()
val flymeFreeformVersionName = providers.gradleProperty("flymeFreeformVersionName").get()

android {
    namespace = "io.github.msecret.flymefreeform"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.msecret.flymefreeform"
        minSdk = 35
        targetSdk = 37
        versionCode = flymeFreeformVersionCode
        versionName = flymeFreeformVersionName
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "debug") "-Debug" else ""
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { version -> "FlymeFreeform-NoRoot-$version$suffix.apk" },
            )
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
