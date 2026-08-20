import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

/**
 * 签名信息从 `local.properties` 读取，**不进版本库**（该文件已在 .gitignore 中）。
 *
 * 需要的四个键见 README「构建与运行 · 打签名包」。任一缺失时，release 构建
 * 退化为产出未签名包（`app-release-unsigned.apk`），构建本身不会失败——
 * 这样克隆本仓库的人无需持有密钥也能完整构建。
 */
val signingProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun signingProp(key: String): String? = signingProps.getProperty(key)?.takeIf { it.isNotBlank() }

val hasSigningConfig =
    listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
        .all { signingProp(it) != null }

android {
    namespace = "tech.illusion.bookofanswers"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.illusion.bookofanswers"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(signingProp("RELEASE_STORE_FILE")!!)
                storePassword = signingProp("RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("RELEASE_KEY_ALIAS")
                keyPassword = signingProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 缺少签名配置时留空，产出未签名包而不是让构建失败。
            signingConfig = if (hasSigningConfig) signingConfigs.getByName("release") else null
            // 保持关闭：Spatial SDK 依赖反射与 ECS 注册，开启混淆需额外的 keep 规则
            // 并重新做一轮设备验证，不宜与打签名包一起动。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.spatial.bom))
    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)
    implementation(libs.spatial.ui.sense)
    implementation(libs.spatial.ui.tracking)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}
