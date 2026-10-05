plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.equimeal.gramo"
    /*
     * compileSdk 用 36：本机 SDK 里装的是 `platforms;android-36`（见 android/README.md 的构建一节）。
     * targetSdk 保持 35 —— targetSdk 决定的是**行为兼容性开关**，升它要逐个复核运行时行为
     * （分区存储、后台启动限制…），与本次「接上蓝牙」无关，所以刻意不动。
     */
    compileSdk = 36
    defaultConfig {
        applicationId = "com.equimeal.gramo"
        minSdk = 24
        targetSdk = 35
        /*
         * 0.1.1：应用名改为 **Gramo**，包名从 `com.equimeal` 改成 `com.equimeal.gramo`。
         *
         * 包名变化同样是一次**不兼容的身份变更**（系统当成另一个应用：不覆盖旧安装、
         * 旧安装的数据也不共享），所以版本号继续往前走。
         * 0.1.0 那一步是 `com.smartspoon.l2` → `com.equimeal`，同样是身份变更。
         */
        versionCode = 5
        versionName = "0.1.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // 让「设置 → 账户设置 → 关于 EquiMeal」直接读 BuildConfig.VERSION_NAME，
        // 版本号就只有 defaultConfig 这一处，不会再出现"界面写着旧版本号"的陈旧文案；
        // 另外 BuildConfig.DEBUG 用来决定"要不要显示给开发看的诊断信息"（见设备管理页）
        buildConfig = true
    }

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

    /*
     * 本地单元测试：`gradlew :app:testDebugUnitTest`
     *
     * 目前跑的是蓝牙协议解析与半包/粘包重组（`src/test/java/.../SpoonProtocolTest.kt`）——
     * 这两块是"错了也不会崩、只是静默给错数据"的地方，最值得在装机之前先钉住。
     * 纯 JVM 测试：不需要真机、不需要模拟器、不需要 SDK 里的任何系统镜像。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
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

    // 协议解析的单元测试（只用最基础的 JUnit，不引入 mock 框架：
    // 被测试的是纯函数与纯字符串处理，不需要假 Context / 假 Looper）
    testImplementation("junit:junit:4.13.2")
}
