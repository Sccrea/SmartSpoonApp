plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.smartspoon.l2"
    compileSdk = 35
    defaultConfig { applicationId = "com.smartspoon.l2"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "0.0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // 沿用项目自带的 debug.keystore（旧的 build.py 管线也是用它签的）：
    // 签名一致，`adb install -r` 才能就地覆盖升级，不会因为签名不符要求先卸载，
    // 从而实现里已有的菜品/用餐记录也不会丢。
    signingConfigs {
        create("projectDebug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("projectDebug") }
        // release 用同一把调试密钥签名，方便 `adb install -r` 就地覆盖 debug 安装做性能对比。
        // 关键在 isMinifyEnabled：debug 构建下 Compose 没有 R8 优化、还带一堆运行时检查，
        // 帧耗时能差好几倍（实测 debug 18ms/帧 vs release 5ms/帧）。
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("projectDebug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}
dependencies {
    // Jetpack Compose + Material3：与本机 Gramophone 同一套技术栈
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // 旧 View 版 UI 尚未删完，先留着
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
