import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    } else {
        rootProject.file("local.default.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
}

fun getLocalProperty(key: String, default: String): String {
    return localProperties.getProperty(key, default)
}

android {
    namespace = "com.palmagent.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.palmagent.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "LLM_API_KEY", "\"${getLocalProperty("LLM_API_KEY", "")}\"")
        buildConfigField("String", "LLM_API_URL", "\"${getLocalProperty("LLM_API_URL", "")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${getLocalProperty("LLM_MODEL", "")}\"")
        buildConfigField("String", "VLM_API_URL", "\"${getLocalProperty("VLM_API_URL", "")}\"")
        buildConfigField("String", "VLM_MODEL", "\"${getLocalProperty("VLM_MODEL", "")}\"")
        buildConfigField("String", "VLM_API_KEY", "\"${getLocalProperty("VLM_API_KEY", "")}\"")
        buildConfigField("String", "KEYBOARD_VLM_API_URL", "\"${getLocalProperty("KEYBOARD_VLM_API_URL", "")}\"")
        buildConfigField("String", "KEYBOARD_VLM_MODEL", "\"${getLocalProperty("KEYBOARD_VLM_MODEL", "")}\"")
        buildConfigField("String", "KEYBOARD_VLM_API_KEY", "\"${getLocalProperty("KEYBOARD_VLM_API_KEY", "")}\"")
        buildConfigField("String", "PLANNER_API_KEY", "\"${getLocalProperty("PLANNER_API_KEY", "")}\"")
        buildConfigField("String", "PLANNER_API_URL", "\"${getLocalProperty("PLANNER_API_URL", "")}\"")
        buildConfigField("String", "PLANNER_MODEL", "\"${getLocalProperty("PLANNER_MODEL", "")}\"")
        // PLANNER_ENABLE_SEARCH 已删除 — 联网搜索由 web_search 工具提供（与执行模型统一）

        // 上下文压缩模型（FailureCompactor 失败信息压缩，默认智谱 GLM-4.5-Flash）
        // 未配置时运行时回退使用决策模型（Planner）配置
        buildConfigField("String", "COMPACT_API_KEY", "\"${getLocalProperty("COMPACT_API_KEY", "")}\"")
        buildConfigField("String", "COMPACT_API_URL", "\"${getLocalProperty("COMPACT_API_URL", "")}\"")
        buildConfigField("String", "COMPACT_MODEL", "\"${getLocalProperty("COMPACT_MODEL", "")}\"")

        // 高德地图 MCP
        buildConfigField("String", "AMAP_API_KEY", "\"${getLocalProperty("AMAP_API_KEY", "")}\"")
        buildConfigField("String", "AMAP_MCP_BASE_URL", "\"${getLocalProperty("AMAP_MCP_BASE_URL", "https://mcp.amap.com/mcp")}\"")

        // 执行模型联网搜索（博查 API + DuckDuckGo 兜底）
        buildConfigField("boolean", "EXECUTION_ENABLE_SEARCH", getLocalProperty("EXECUTION_ENABLE_SEARCH", "true"))
        buildConfigField("String", "BOCHA_API_KEY", "\"${getLocalProperty("BOCHA_API_KEY", "")}\"")
        buildConfigField("String", "GUI_OWL_API_KEY", "\"${getLocalProperty("GUI_OWL_API_KEY", "")}\"")
        buildConfigField("String", "GUI_OWL_API_URL", "\"${getLocalProperty("GUI_OWL_API_URL", "")}\"")

        // 阿里云百炼 DashScope API Key（GUI-Plus 界面交互模型）
        // 未单独配置时回退使用 LLM_API_KEY（同为百炼 Key，共用账号额度）
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"${getLocalProperty("DASHSCOPE_API_KEY", getLocalProperty("LLM_API_KEY", ""))}\"")
        // 端侧知识库：无服务端依赖，无需 KB_API_URL 配置
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        buildConfig = true
    }
    aaptOptions {
        noCompress("tflite", "onnx")
    }
    packaging {
        jniLibs {
            // sherpa-onnx static AAR 的 x86 目录冗余携带 libonnxruntime.so（其自身已静态链接 onnxruntime），
            // 与 onnxruntime-android 的同名 .so 冲突 → 任取其一即可（arm64 真机不受影响，AAR 该 ABI 无此 .so）
            pickFirsts += "lib/x86/libonnxruntime.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation("io.github.hzkitty:rapidocr4j-android:1.0.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
    // sherpa-onnx（官方 SenseVoice ASR）：static-link 版 arm64 仅含 libsherpa-onnx-jni.so（已静态链接 onnxruntime），
    // 与上方 onnxruntime-android（VAD 用）不冲突
    implementation(files("libs/sherpa-onnx-static-1.13.6.aar"))
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}