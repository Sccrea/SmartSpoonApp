# 智味勺 · Jetpack Compose Android 应用 + Flask 数据服务器

按设计稿实现的移动端应用：**界面是 Jetpack Compose + Material 3**
（页面风格对齐 Gramophone：Material You 动态取色、大标题栏、MD3 列表行与底部导航），
**服务器只提供 JSON 数据接口**（网页端已废弃）。

> **设计稿不在仓库里**（原始 PDF 约 7.8MB，逐页导出的 PNG 也在 `.gitignore` 里）。
> 设计稿下载：<https://wwbwa.lanzoue.com/iu9GU486xhva>
> 界面配色与间距都是自 1200×2134 的设计稿 PNG 逐像素取色的。

```
┌────────────────────────────┐         ┌──────────────────────────────┐
│  Android 应用（Kotlin 原生） │  HTTP   │  Flask 服务器（纯 JSON 接口）  │
│  · 启动时 GET /api/bootstrap│ ──────► │  · 菜品库落盘 data/dishes.json │
│  · 拍照识别 POST /api/recognize │ ◄── │  · 百度菜品识别               │
│  · 新增菜品 POST /api/dishes│         │  · 识别历史                   │
└────────────────────────────┘         └──────────────────────────────┘
```

## 目录结构

```
test/
├─ run.py                  # 启动数据服务器（默认 0.0.0.0:5000）
├─ requirements.txt
├─ .env.example            # 密钥与端口配置模板
├─ app/                    # Flask 服务器（只有 JSON 接口，没有网页）
│  ├─ __init__.py          # 应用工厂
│  ├─ config.py            # 配置（密钥、上传目录、菜品库文件位置）
│  ├─ routes.py            # /api/* 接口
│  ├─ dishes.py            # 菜品库存储（落盘 data/dishes.json）
│  ├─ baidu_dish.py        # 百度菜品识别客户端
│  └─ data.py              # 首次运行的示例数据
├─ data/dishes.json        # 菜品库（应用保存的菜品在这里持久化）
├─ android/                # Compose + Material 3 应用（Gradle 工程）
│  ├─ app/src/main/java/com/smartspoon/l2/
│  │  ├─ MainActivity.kt   # 数据加载与探测、相机与权限、轮询、用餐计时（只管 Activity 该管的事）
│  │  ├─ ui/Theme.kt       # Material You 动态取色主题（低版本退回 Gramophone 蓝色 palette）
│  │  ├─ ui/App.kt         # 外壳：大标题栏 + NavigationBar + 步骤条 + 已选托盘 + 路由
│  │  ├─ ui/Components.kt  # ListRow / CoverBox / ConfigRow / SwitchRow / StepBar 等组件
│  │  ├─ ui/AppScreens.kt  # 快速开始 / 选择菜单 / 食物列表 / 收藏
│  │  ├─ ui/RecordsStatsLiveScreens.kt  # 用餐记录 / 统计图表 / 用餐中 / 用餐结果
│  │  ├─ ui/SettingsScreens.kt          # 设置 / 设备 / 菜单 / 杂项 / 账户
│  │  ├─ Data.kt           # 数据模型、JSON 解析、HTTP 客户端、全局状态（Compose 可观察）
│  │  ├─ Store.kt          # 本地 SQLite（菜品/菜单/用餐记录）
│  │  └─ ShotProvider.kt   # 相机写文件用的极简 ContentProvider
│  ├─ app/src/main/res/    # 主题、字符串、图标
│  ├─ build.gradle.kts     # AGP 8.5.2 / Kotlin 2.0.21 / Compose BOM 2024.10.01
│  └─ README.md            # 构建、安装、接口与页面结构说明
├─ tools/smoke.py          # 服务器接口冒烟测试（不需要真起服务器）
├─ _tools/                 # 本机工具链：gradle-8.9 / jdk17（体积大，未入库）
├─ _design/                # 设计稿页面 PNG 与还原说明（本地参考材料，未入库）
└─ uploads/                # 识别时上传的图片（运行时生成）
```

## 运行

### 1. 启动数据服务器

```powershell
cd C:\Users\Administrator\Desktop\test
.\.venv\Scripts\python.exe run.py
```

默认监听 `0.0.0.0:5000`，启动时会打印本机与局域网地址。`HOST` / `PORT` 可覆盖。

### 2. 构建并安装应用

Gradle 由 wrapper 自带（`android/gradlew`，首次运行自动下载），签名用的 keystore 随仓库提交，
所以克隆下来只需要自备 **JDK 17** 与 **Android SDK**：

```powershell
# 前置：JDK 17（设好 JAVA_HOME）+ Android SDK
#       把 android\local.properties.example 复制成 local.properties 并填上 SDK 路径
cd android
Copy-Item local.properties.example local.properties   # 然后编辑 sdk.dir
$env:JAVA_HOME = "<你的 JDK 17 路径>"

.\gradlew.bat :app:assembleRelease     # 发布版（R8 压缩）：app\build\outputs\apk\release\app-release.apk
.\gradlew.bat :app:assembleDebug       # 调试版：app\build\outputs\apk\debug\app-debug.apk

cd ..
.\.venv\Scripts\python.exe tools\smoke.py             # 服务器接口自检（不用先起服务器）

adb install -r android\app\build\outputs\apk\release\app-release.apk
```

> macOS / Linux 上把 `.\gradlew.bat` 换成 `./gradlew` 即可，其余一样。

详见 `android/README.md`（签名、长列表架构等）。

### 3. 在应用里指定服务器

应用启动时会依次尝试默认地址（`http://10.0.2.2:5000`、`http://192.168.14.128:5000`）
和已保存的地址，连上后读取菜品信息；也可以随时在 **设置 → 杂项 → 服务器地址** 里
「修改地址」或「重新读取菜品信息」。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 接口清单（JSON，不是网页） |
| GET | `/api/health` | 健康检查（含菜品数量） |
| GET | `/api/bootstrap` | **应用启动时读取**：菜品库 + 菜单 + 收藏夹 + 记录 + 统计 + 图表 + 设备 + 用户 |
| GET | `/api/dishes` | 菜品库 + 菜单 |
| POST | `/api/dishes` | 新增/更新一道菜 `{name, category, density, times}`，落盘保存 |
| POST | `/api/dishes/<id>/eat` | 记录一次食用（times +1） |
| DELETE | `/api/dishes/<id>` | 删除一道菜 |
| POST | `/api/recognize` | 上传图片识别菜品（`multipart/form-data` 字段 `image`，或 JSON `image_base64`） |
| GET | `/api/history` · POST `/api/history/clear` | 识别历史 |
| GET | `/uploads/<file>` | 查看上传过的图片 |

## 界面与设计稿对应关系

| 设计稿页 | 界面 | 入口 |
| --- | --- | --- |
| p.02 | 餐前设置 · 快速开始（两张卡片） | 底部「快速开始」 |
| — | 选择本餐菜单（点一份已有菜单直接开始） | 快速开始 → 使用现有菜单快速开始 |
| p.03 | 自定义本餐菜单（搜索 / 分类 / 排序 / 添加到菜单 / 选好了） | 快速开始 → 自定义本餐菜单 |
| p.04 | 连接智味勺弹窗（单设备） | 「选好了」/ 选中某份菜单 |
| p.05 | 连接智味勺弹窗（附近设备列表） | 「重新选择」 |
| p.06 | 用餐提醒弹窗 | 「开始用餐」 |
| p.07 | 用餐中（实时数据 + 退出记录 / 暂停记录 / 结束用餐） | 「我已知晓」 |
| p.08 / p.09 | 用餐结果（可修正数据 + 完成） | 用餐中 →「结束用餐」 |
| p.10 | 收藏食物（收藏夹 + 收藏列表） | 底部「收藏食物」 |
| p.11 | 用餐记录列表 | 底部「用餐记录」 |
| p.12 / p.13 | 统计数据（汇总 / 折线图） | 用餐记录 →「统计数据」 |
| p.14 | 设置 | 底部「设置」 |
| p.15 / p.16 | 设备管理（附近 / 已保存） | 设置 → 设备管理 |
| p.17 | 菜单管理 | 设置 → 菜单 |
| p.18 | 杂项（时间 / 单位 / **服务器地址**） | 设置 → 杂项 |

> 餐前设置的三个页面与用餐结果页**始终显示底部四个标签栏**（设计稿 p.02 / p.03 / p.08 本身都带）；
> 设置各子页左上角的「‹ 设置」可点击返回。

## 设计稿配色

自 1200×2134 的设计稿 PNG 逐像素取色（设计稿下载：<https://wwbwa.lanzoue.com/iu9GU486xhva>；
逐页还原说明留在本地 `_design/design_notes.md`，未入库）：

| 用途 | 色值 |
| --- | --- |
| 页面底色 | `#F0F0F0`，卡片 `#FFFFFF`，托盘 `#F5F5F5` |
| 描边按钮与强调绿 | `#A6D640` |
| 三步进度条：当前步 / 未完成 | `#00FF00` / `#FF0000` |
| 青绿（弹窗文字按钮、选好了） | `#2BBDBD` |
| 用餐中按钮：退出 / 暂停 / 结束（黑字） | `#FF0000` / `#FFFF00` / `#00FF00` |

## 说明

- 界面动效全部用系统能力实现：所有按钮都有**从按压点展开、裁进按钮形状的水波反馈**（类似
  Gramophone 播放/暂停按钮），弹窗是**系统原生圆角 Dialog**（平台自带的进出场动画）；
  页面加载不做弹出/淡入动画。详见 [`android/README.md`](android/README.md)。
- 设计稿的食物图是实拍照片，原生应用用 emoji + 圆角方块代替；头像为简笔画。
- 用餐中的实时数值按计时模拟（`MainActivity.startTicker()`），接入真实设备时替换该处即可。
- 「拍照识别菜品」识别出的新菜可以直接**保存到服务器菜品库**（`POST /api/dishes`），
  下次启动应用就能读到。
