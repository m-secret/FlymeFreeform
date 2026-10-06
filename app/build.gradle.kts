plugins {
    // AGP 9 自带 Kotlin 支持，本模块刻意不声明 org.jetbrains.kotlin.android。
    // 额外声明会让 Gradle 认为插件已在 classpath 且版本未知，直接报错。
    alias(libs.plugins.android.application)
}

val flymeFreeformVersionCode = providers.gradleProperty("flymeFreeformVersionCode").get().toInt()
val flymeFreeformVersionName = providers.gradleProperty("flymeFreeformVersionName").get()

// 固定签名：本地与 CI 共用仓库里这一份密钥，签出来的证书指纹一致，
// 否则两边的包会因「包名相同、签名不同」互相拒绝覆盖安装。
// 用 PKCS12 存储并显式声明 storeType，避免依赖 JDK 的默认 KeyStore 类型。
val sharedStoreFile = rootProject.file(
    providers.gradleProperty("flymeFreeformStoreFile").getOrElse("keystore/flymefreeform.jks"),
)
val sharedStorePassword = providers.gradleProperty("flymeFreeformStorePassword").getOrElse("flymefreeform")
val sharedKeyAlias = providers.gradleProperty("flymeFreeformKeyAlias").getOrElse("flymefreeform")
val sharedKeyPassword = providers.gradleProperty("flymeFreeformKeyPassword").getOrElse("flymefreeform")

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

    // 密钥缺失时（例如只拷走了源码）退回默认调试签名，保证仍能构建，只是签名不稳定。
    val sharedSigning = if (sharedStoreFile.exists()) {
        signingConfigs.create("shared") {
            storeFile = sharedStoreFile
            storeType = "PKCS12"
            storePassword = sharedStorePassword
            keyAlias = sharedKeyAlias
            keyPassword = sharedKeyPassword
        }
    } else {
        logger.warn("[flymefreeform] 未找到签名密钥 ${sharedStoreFile.path}，回退到默认调试签名。")
        null
    }

    buildTypes {
        getByName("debug") {
            sharedSigning?.let { signingConfig = it }
        }
        release {
            sharedSigning?.let { signingConfig = it }
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
        // **产物名不带 buildType 后缀**（用户 2026-10-06 发 1.0.0 时要求：「1.0.0 就不要加 debug 了」）。
        // 两种 buildType 落在各自的 `outputs/apk/<type>/` 目录里，同名不会互相覆盖；
        // 名字只跟 versionName 走，发出去的包（Release 附件）就叫 `FlymeFreeform-<version>.apk`。
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { version -> "FlymeFreeform-$version.apk" },
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
