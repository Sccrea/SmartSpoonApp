# 智味勺 L2 · Jetpack Compose + Material 3 Android 应用

界面全部用 **Jetpack Compose + Material 3** 描述（没有 WebView、没有 XML 布局），
数据来自 Flask 服务器的 JSON 接口。构建走 **Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.21 + Compose BOM 2024.10.01**。

页面风格对齐 **Gramophone**（本机 `Desktop\Gramophone` 那份源码）：**Material You 动态取色**、
Large collapsing top app bar、MD3 列表行与底部 NavigationBar。

| 项 | 值 |
| --- | --- |
| 包名 | `com.smartspoon.l2` |
| 版本 | 0.0.1（versionCode 1） |
| minSdk / targetSdk | 24 / 35 |
| 入口 | `com.smartspoon.l2.MainActivity` |
| 产物 | `app/build/outputs/apk/release/app-release.apk`（发布版）/ `...\debug\app-debug.apk`（调试版） |
| 源码 | `android/app/src/main/java/com/smartspoon/l2/`（UI 在 `ui/` 子包） |

## 与其他部分的关系

```
Android 应用（Kotlin 原生界面）
   │ 启动时 GET /api/bootstrap           ← 菜品库 / 菜单 / 记录 / 设备 / 用户
   │ 拍照后 POST /api/recognize           → 百度菜品识别
   │ 识别新菜 POST /api/dishes            → 落盘 data/dishes.json
   ▼
Flask 服务器（只有 JSON 接口，没有网页）
```

服务器地址在 **设置 → 杂项 → 服务器地址** 里修改（也可以「重新读取菜品信息」立刻重载）。
默认地址是 **`https://sccrea64.cc.cd:16384`**（`State.DEFAULT_SERVER`，见 `Data.kt`）；
启动时会依次探测：已保存的地址 → 默认地址，第一个 `/api/health` 返回 200 的即被采用。

## 源码结构

| 文件 | 作用 |
| --- | --- |
| `ui/Theme.kt` | **Material You 动态取色**主题：API 31+ 从壁纸取色，低版本/取色失败退回 Gramophone 那套蓝色 palette |
| `ui/App.kt` | 应用外壳：`Scaffold` + Large collapsing top app bar + MD3 `NavigationBar`，并保留三步骤进度条与底部「已选食物」托盘；含页面路由 |
| `ui/Components.kt` | 组件词汇表：`ListRow`（Gramophone 行度量）、`CoverBox`、`ConfigRow`、`SwitchRow`、`PillButton`、`FoodRow`、`StepBar`、`TrayBar` 等 |
| `ui/AppScreens.kt` | 快速开始 / 选择本餐菜单 / 自定义本餐菜单 / 收藏食物 |
| `ui/RecordsStatsLiveScreens.kt` | 用餐记录 / 统计数据（Compose `Canvas` 折线图与柱状图）/ 用餐中 / 用餐结果 |
| `ui/SettingsScreens.kt` | 设置 / 设备管理 / 菜单 / 杂项 / 账户设置 |
| `MainActivity.kt` | 主界面（四个标签页）的宿主；其余页面都是独立 Activity，所以它只剩导航、弹窗与本地库读取 |
| `AppCore.kt` | 进程级单例：**唯一**一份 `Store`、提示出口、重读本地库、服务器探测与导入、菜品增删改与弹窗动作。挂在 `Application` 上，与任何 Activity 的生死无关 |
| `Units.kt` | **单位换算与格式化的唯一出口**：热量的 kJ↔kcal、重量的 g↔kg↔两、时间戳→文本（受「时间显示年 / 秒」控制） |
| `MealSession.kt` | 一次用餐的会话状态：每一口、计时、设备轮询、餐次生命周期。进程级，不持有 Context |
| `ui/MealFlowActivities.kt` | 用餐流程的四个独立 Activity（选择本餐菜单 / 自定义本餐菜单 / 用餐中 / 用餐结果）+ `MealFlowHost` 接口 + 底部托盘 |
| `ui/PageShell.kt` | 「一页 = 自己的标题栏 + 内容」的公共外壳（`BasePageActivity`），设置子页与用餐流程共用 |
| `ui/SettingsActivities.kt` | 设置四个子页 + 统计数据（独立 Activity）与 `SettingsHost` |
| `PhotoRecognizer.kt` | 拍照 / 选图 / 上传识别，宿主持有一份 |
| `Data.kt` | 数据模型、`org.json` 解析、`HttpURLConnection` 客户端、全局状态 `State`（**字段都是 Compose 可观察属性**） |
| `Store.kt` | 本地 SQLite：菜品 / 菜单 / 用餐记录 / 每一口，离线可用 |
| `ShotProvider.kt` | 相机写文件用的极简 `ContentProvider`（替代 AndroidX FileProvider） |

### 单位与时间显示是真的生效的

「设置 → 杂项」里那四项（时间显示年 / 时间显示秒 / 热量单位 / 重量单位）全部落到 `Units.kt`：

- **内部只存基准单位** —— 热量存 kJ、重量存 g、时间存毫秒时间戳（`dishes.favorite_at_ms`、`meals.started_at/ended_at`）；
- **只在显示的那一刻换算** —— 界面、弹窗、统计、折线图都调 `Units`，不再各自拼 `"${value} kJ"`。

所以改单位/开关，所有位置下一帧就跟着变；只有「统计数据汇总」与折线图的文字是读库时算好的，
设置页改完会顺手重读一次本地库。数据库 v1 → v2 只做**加列 + 回填**（老的 `favorite_at` 文本解析成时间戳），
不会重建表、不丢数据。

### 设置行的交互约定

**整行可点**：开关行点标题 / 说明 / 开关任意位置都切换；下拉选择行点标题也能把列表弹出来
（`ui/Components.kt` 的 `PrefSwitchRow` / `SwitchRow` / `PrefSelectRow`，
以及统计数据页的 `SelectRow`）。开关自身保留 `onCheckedChange`，Compose 的子节点点击不会向上冒泡，
所以点开关只会切换一次，选中态也照旧留在无障碍树里。

### 状态是唯一的真相来源（迁移的关键设计）

`State` / `MealState` / `MealResult` / `Device` 的可变字段全部用 `mutableStateOf` / `mutableStateListOf` 承载。
这样做的价值是：**Store、网络、相机、轮询、计时那些逻辑的读写写法一个字都不用改，界面却会自动重组**。
于是旧版那套 `render()` / `renderContentOnly()` 的「整页重建」机制彻底不需要了——两者现在都是空实现，
只为兼容既有调用点而保留签名。

> **注意源码位置**：全部源码只有两处 —— `android/app/src/main/java/com/smartspoon/l2/`
> （界面在 `ui/` 子包）与 `android/app/src/main/res/`。
> 切到 Gradle **之前**那套老管线的遗留副本（`android/kotlin/`、`android/res/`、
> 根目录的 `AndroidManifest.xml`）已经全部清理掉，仓库里不存在这些目录。

## 构建

克隆下来**只靠自己就能构建**，不需要任何仓库外的工具链：
Gradle 由 wrapper（`android/gradlew`）自带并按需下载，签名用的 keystore 随仓库提交。

前置条件只有两样：

1. **JDK 17**（AGP 8.5.2 要求），设好 `JAVA_HOME`；
2. **Android SDK**（platform 35 + build-tools），路径写进 `android/local.properties`。

```powershell
cd android

# 1) 配置 SDK 路径（local.properties 是机器相关的，不入库，所以由示例复制而来）
Copy-Item local.properties.example local.properties
#    然后编辑 local.properties，把 sdk.dir 改成本机的 Android SDK 路径；
#    也可以不建这个文件，改为设置环境变量 ANDROID_HOME。

# 2) 构建（wrapper 首次运行会自动下载 Gradle 8.9）
$env:JAVA_HOME = "<你的 JDK 17 路径>"
.\gradlew.bat :app:assembleDebug        # macOS / Linux 用：./gradlew :app:assembleDebug

# 3) 发布版（R8 压缩 + 资源裁剪）
.\gradlew.bat :app:assembleRelease      # macOS / Linux 用：./gradlew :app:assembleRelease
#    产物：app/build/outputs/apk/release/app-release.apk

# 4) 安装
adb install -r app\build\outputs\apk\release\app-release.apk
```

数据服务器是可选的：应用**不连服务器也能完整使用**（菜品、菜单、用餐记录、每一口都在本地
SQLite 里，首次安装会写入示例菜品库）；只有「拍照识别」和「从服务器导入菜品库」需要它。

```powershell
# 可选：启动数据服务器
cd ..                                          # 回到仓库根目录
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
Copy-Item .env.example .env
.\.venv\Scripts\python.exe run.py
```

构建工具链：

| 项 | 值 |
| --- | --- |
| Gradle | wrapper（`android/gradlew`，8.9，首次运行自动下载） |
| JDK | 17（`JAVA_HOME`） |
| AGP / Kotlin | 8.5.2 / 2.0.21（`android/build.gradle.kts`） |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |
| Compose | BOM 2024.10.01：`material3`、`ui`、`material-icons-extended`、`activity-compose` |

> Compose 编译器随 Kotlin 版本走，所以 `build.gradle.kts` 里必须应用
> `org.jetbrains.kotlin.plugin.compose`（与 Kotlin 同为 2.0.21），并开 `buildFeatures { compose = true }`。

**签名**：`app/build.gradle.kts` 里的 `signingConfigs.projectDebug` 复用仓库自带的
`android/debug.keystore`（别名 `androiddebugkey`，口令 `android`，与老 `build.py` 管线同一把钥匙）。
签名一致，`adb install -r` 才能就地覆盖升级；否则会因签名不符要求先卸载，本地数据库里的菜品与用餐记录会一起丢掉。

> 这个 keystore **随仓库提交**（`.gitignore` 里没有忽略它）：签名配置引用了这个文件，
> 不提交的话别人克隆下来 `assembleDebug` 会直接失败。它只是调试密钥、口令就是公开约定的
> `android`，所以不算机密；真要发布正式版请另建 release keystore，并改用环境变量注入口令。

## 页面结构（对齐 Gramophone）

```
Scaffold
├── LargeTopAppBar      大标题栏，滚动时折叠（contentScrim = surfaceContainer）
│                       自定义本餐菜单页改用普通高度标题栏，理由见下
├── 内容                LazyColumn / Column，各页面自己滚动
└── bottomBar           「已选食物」托盘（仅在选菜页）+ MD3 NavigationBar
```

- **列表行度量直接取自 Gramophone** 的 `res/values/dimens.xml` 与 `layout/adapter_list_card.xml`：
  行高 75dp、封面 50dp（圆角 6dp）、行内左右 6dp、封面与文字间距 18dp、
  标题 17sp、副标题 14sp、尾部图标控件 48dp。**行本身不铺背景色、不用分隔线。**
- **列表页的内容整体在大标题栏下滚动**（不是固定头）。这一点不是可选项：
  大标题栏本身占 112dp，再叠上「步骤条 + 搜索 + 分类 + 排序」四层固定头，
  留给列表的高度会归零（实测一行都看不见）。滚动时标题栏折叠成小标题，列表能拿回全部高度。
  同理，**自定义本餐菜单**页还要再钉住托盘和底部两个按钮，所以它单独用普通高度的标题栏。
- 设计稿独有的两处结构按需求保留：**三步骤进度条**与**底部「已选食物」托盘**，
  但配色改吃 MD3（已到达的步骤用 `primary`，未到达用 `outlineVariant`）。
- 系统返回键会先在应用内部回退（`BackHandler`），而不是直接退出应用。

## 已实现的功能

- **启动即读数据**：一次 `/api/bootstrap` 拿到全部数据，断网时显示「设置服务器地址 / 重新读取」。
- **完整用餐流程**：快速开始 → 选择本餐菜单（或自定义选菜）→ 连接智味勺 → 用餐提醒 → 用餐中
  （退出 / 暂停 / 结束，实时数值按计时模拟）→ 用餐结果（可修正数据、完成）。
- **拍照识别**：系统相机或图库选图 → `POST /api/recognize` → 列出 Top N；
  命中本地菜品可直接「加入菜单」，**陌生的菜可以「保存到菜品库」写回服务器**（`POST /api/dishes`）。
- **设置**：设备管理（附近 / 已保存、自动连接开关、连接/断开/保存）、菜单、杂项
  （时间显示年/秒、热量与重量单位、**服务器地址**）、账户设置。
- **底部四个标签栏**在餐前设置三页与用餐结果页始终显示；设置子页用大标题栏左上角的返回箭头回退。

## 动效

全部来自 Material 3 组件与 Compose 自带能力，没有引入任何动画库：

| 动效 | 用在哪里 | 实现 |
| --- | --- | --- |
| 触摸水波 | 所有按钮与可点元素 | MD3 组件（`Button` / `Card` / `ListItem` / `NavigationBarItem`…）与 `Modifier.clickable` 自带的水波，从按下位置展开、裁在控件自身形状里 |
| 大标题折叠 | 所有列表页 | `LargeTopAppBar` + `exitUntilCollapsedScrollBehavior()`，滚动时大标题折叠成小标题 |
| 圆角弹窗 | 连接智味勺 / 用餐提醒 / 服务器地址 / 识别结果 / 修正数据… | MD3 `AlertDialog`；内容较多的几个（连接、服务器地址、菜品编辑…）用 28dp 圆角 `Surface` 自绘对话框 |
| 折线图生长 | 统计数据第 2 页 | Compose `Canvas` + `Animatable` 0→1（800ms，`FastOutSlowInEasing`，与旧的 `Motion.standard` 同曲线），线条从左往右长出来 |
| 实时数值脉冲 | 用餐中 | 数值变化时轻微放大回弹（数据变化提示，非页面加载动画） |
| 下拉选择 | 杂项的单位、设备管理的作用域、统计数据页的 5 个选项 | MD3 `DropdownMenu` / `FilterChip` |

> **页面加载不做动画**：切页面、切标签、进列表都是直接就位，没有淡入/上移/逐条弹出。

## 实现上的注意点

- **页面状态就是 `State`**，不要写「重画页面」的代码：改状态即重组。
  旧版的 `render()` / `renderContentOnly()` 保留为空实现，只为让既有业务逻辑的调用点不用改。
- **宽控件会挤坏行内容**（都是装机后才看出来的）：
  - Gramophone 的行尾部只放 48dp 图标控件。菜品行的「添加到菜单」做成带字胶囊时，
    副标题会被挤成「能量密度: 2.1…」，所以改成 ＋/✓ 图标按钮，选中状态由托盘体现；
  - 设备行的「取消保存 / 断开连接」两个 MD3 按钮并排会把设备名挤成「张三…」，所以改成竖排。
- **托盘里的 ⊗ 不用 `IconButton`**：它会强制 48dp 最小触控区，把已选 chip 挤成「苹…」；
  直接给 `Text("⊗")` 挂 `clickable` 更省地方。
- **相机权限走 Activity Result API**：`ComponentActivity` 上 `onRequestPermissionsResult`
  已不能像以前那样覆盖。
- **签名沿用仓库自带的 `android/debug.keystore`**（见构建一节），否则 `adb install -r`
  会因签名不符要求先卸载，本地库里的菜品与用餐记录会一起丢。
- 相机输出用自带的 `ShotProvider`（`content://com.smartspoon.l2.shots/...`）交给相机应用，
  避免 `FileUriExposedException`。
- 食物缩略图用 emoji + 圆角方块（只挑 Unicode 6.0 以内的字符，模拟器自带字体较旧）。
