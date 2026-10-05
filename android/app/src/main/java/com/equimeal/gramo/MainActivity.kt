package com.equimeal.gramo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.equimeal.gramo.ui.DishAction
import com.equimeal.gramo.ui.FoodPickerActivity
import com.equimeal.gramo.ui.MealDetailActivity
import com.equimeal.gramo.ui.MenuChooserActivity
import com.equimeal.gramo.ui.SmartSpoonApp
import com.equimeal.gramo.ui.SmartSpoonTheme

/**
 * 智味勺 L2 · Jetpack Compose + Material 3 实现 —— 阶段 3 之后这里就只剩**主界面**。
 *
 * 主界面 = 快速开始 / 收藏食物 / 用餐记录 / 设置四个标签（见 `ui/App.kt`）。
 * 其余页面全部是独立 Activity，各自带标题栏与自己的返回栈：
 *
 * - 用餐流程四个页面 → `ui/MealFlowActivities.kt`（选择本餐菜单 / 自定义本餐菜单 / 用餐中 / 用餐结果）
 * - 设置四个子页 + 统计数据 → `ui/SettingsActivities.kt`
 *
 * 于是本类不再承担这些事：
 * - 本地库与「重读本地库」→ [AppCore]（进程级，谁在前台都不影响）
 * - 用餐计时、设备轮询、餐次生命周期 → [MealSession] / [MealSessionActions]
 * - 菜品增删改、各类弹窗动作 → [AppCore]
 * - 相机、权限、上传识别 → [PhotoRecognizer]
 */
class MainActivity : ComponentActivity() {

    /*
     * 拍照 / 选图 / 上传识别（这一层只剩弹窗里那个「拍照识别菜品」会用到）。
     *
     * 同 [BaseMealActivity]：这里必须是饿汉式字段初始化，不能 `by lazy` ——
     * `registerForActivityResult` 只允许在 Activity STARTED 之前注册。
     */
    private val photos = PhotoRecognizer(this)

    /**
     * 蓝牙权限（扫描 / 连接智味勺）。必须和 [photos] 一样饿汉式初始化 ——
     * 理由见 [BlePermissions] 的类注释（`registerForActivityResult` 的注册时机）。
     */
    private val ble = BlePermissions(this)

    /**
     * 弹窗宿主能力（见 [DialogActions]）。
     *
     * 动作本体已经搬到 [AppCore]（用餐流程那四个 Activity 用的是**同一份**），
     * 这里只把「需要 Context 的那两个」接回本类。
     */
    val dialogs: DialogActions = object : DialogActions {
        override fun closeOverlay() = AppCore.closeOverlay()
        override fun showConnectDialog(picking: Boolean) = AppCore.showConnectDialog(picking)
        override fun selectConnectedDevice(deviceId: String) = AppCore.selectConnectedDevice(deviceId)
        override fun zeroSpoonWeight() = AppCore.zeroSpoonWeight()
        // 查找/连接真机勺子的入口：主界面这一层只管要权限，连上之后状态在 BleSpoon 里
        override fun scanSpoons() = ble.ensureReady { SpoonLink.startScan() }
        override fun connectSpoon(address: String, name: String) =
            ble.ensureReady {
                // 内层把本页的权限/开蓝牙能力交给 BleSpoon（理由见 SettingsActivities 里的同名方法）
                BleSpoon.connect(
                    this@MainActivity,
                    BleSpoon.BleReady { ready -> ble.ensureReady(ready) },
                    address,
                    name,
                )
            }
        override fun disconnectSpoon() = BleSpoon.disconnect()
        override fun grantBlePermission() = ble.ensure { }
        override fun bleReady(onReady: () -> Unit) = ble.ensureReady(onReady)
        override fun showRenameDevice(deviceId: String) = AppCore.showRenameDevice(deviceId)
        override fun renameDevice(deviceId: String, name: String): String =
            AppCore.renameDevice(deviceId, name).also { toast(it) }
        // 用餐提醒只由用餐流程那四个 Activity 弹出，主界面上这一项不可达
        override fun confirmRemindAndStart() = AppCore.closeOverlay()
        override fun startMeal() = AppCore.startMeal()
        override fun currentBiteList(): List<Bite> = MealSession.currentBites.toList()
        override fun mealBites(mealId: Long): List<Bite> = MealSession.mealBites(mealId)
        override fun saveResultField(field: String, value: String) {
            toast(AppCore.saveResultField(field, value))
        }
        override fun deleteMealConfirmed(mealId: Long) = toast(AppCore.deleteMeal(mealId))
        override fun saveDishFromEditor(foodId: String?, name: String, category: String, density: Double) {
            toast(AppCore.saveDish(foodId, name, category, density))
        }
        override fun deleteDishConfirmed(foodId: String) = toast(AppCore.deleteDish(foodId))
        override fun addRecognizedToMenu(foodId: String) = toast(AppCore.addRecognizedToMenu(foodId))
        override fun saveDishToServer(name: String, calorie: Double) {
            AppCore.saveDishToServer(name, calorie) { toast(it) }
        }
        override fun saveServerAddress(text: String) = this@MainActivity.saveServerAddress(text)
        override fun goOfflineDemo() = this@MainActivity.goOfflineDemo()
        override fun takePhoto() = photos.takePhoto()
        override fun pickImage() = photos.pickImage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        State.load(this)

        // 界面整棵交给 Compose；所有页面状态都在 State 里，不需要手动触发重绘。
        // 本地库、提示出口与「重读本地库」这三个挂钩现在挂在 Application 上（见 AppCore），
        // 与任何 Activity 的生死无关。
        setContent {
            SmartSpoonTheme {
                SmartSpoonApp(this)
            }
        }

        AppCore.loadFromStore()
        AppCore.detectServer()
        // 登录态：启动时按上次登录的账号切到它自己的本地库，再后台校验令牌是否还有效
        AppCore.switchAccount(State.account?.username)
        AppCore.verifySession()
        // 菜品库是所有账号共用的一份、存在服务器上：启动就静默对齐一次（离线则用本地缓存）
        AppCore.syncDishesFromServer("启动")
        // 用餐记录与食用次数按账号存在云端：登录状态下启动也对齐一次。
        // 注意顺序 —— 要放在 switchAccount 之后，否则对齐的是上一个账号的库。
        AppCore.syncCloudData("启动")

        // 上次连过的勺子：打开应用就回连（受「设置 → 设备管理 → 自动连接」开关控制）。
        // 它只发一次连接请求，失败也不会反复弹窗；具体状态在「设备管理」里看得到。
        BleSpoon.autoConnectIfRemembered(this)
    }

    /* ------------------------------------------------------------ 数据加载 */

    /** 可选：从服务器导入菜品库（识别等功能仍需要服务器）。 */
    fun importFromServer() = AppCore.importFromServer { message -> toast(message) }

    fun reloadFromServer() {
        importFromServer()
    }

    /* -------------------------------------------------------------- 导航 */

    /*
     * 阶段 3 起这里只剩「打开另一个 Activity」：
     * 快速开始的两张卡、以及底部标签自身。标题栏、返回箭头与转场都由目标 Activity 负责。
     */

    fun selectTab(index: Int) {
        State.tab = index
        State.screen = Screen.Tab
        State.statsPage = 1
        if (index == 3) State.settingsView = ""
        if (index == 1) State.folderId = null
    }

    /** 快速开始 →「使用现有菜单快速开始」（p.02 选择本餐菜单）。 */
    /** 快速开始 →「使用现有菜单快速开始」：列菜单前先对齐服务器上的菜品库。 */
    fun openMenuChooser() {
        AppCore.syncDishesFromServer("选择菜单")
        startActivity(Intent(this, MenuChooserActivity::class.java))
    }

    /** 快速开始 →「自定义本餐菜单」（p.03 食物列表）。 */
    fun openFoodPicker() {
        // 进选菜页要**重置筛选**：这两个是全局状态，上一个页面的选择会留在这里，
        // 表现就是"明明有菜却提示没有匹配的食物"（现场踩过）。
        State.query = ""
        State.category = "全部"
        // 菜品库在服务器上（所有账号共用一份），进选菜页前顺手同步一次：
        // 别人刚加的菜、或换了手机之后，这里就能看到
        AppCore.syncDishesFromServer("自定义本餐菜单")
        startActivity(Intent(this, FoodPickerActivity::class.java))
    }

    /* ------------------------------------------------------ 菜品与记录 */

    /** 菜品行的 ⋮ 菜单：收藏 / 编辑 / 删除（对应 Compose 的 DropdownMenu）。 */
    fun handleDishAction(food: Food, action: DishAction) {
        when (action) {
            DishAction.ToggleFavorite -> toast(AppCore.toggleFavorite(food))
            DishAction.Edit -> AppCore.showDishEditor(food)
            DishAction.Delete -> AppCore.confirmDeleteDish(food)
        }
    }

    /**
     * 用餐记录详情：打开**独立详情页**（与用餐结果页同一套界面，数据可改）。
     *
     * 以前这里弹的是一个只读的明细弹窗；现在改成整页，因为"查看并修改"塞不进弹窗 ——
     * 名称、时长、口数、总重量、总热量、平均两项、每一口明细、删除记录，一页才排得下。
     */
    fun showMealDetail(record: MealRecord) {
        startActivity(MealDetailActivity.intentFor(this, record))
    }

    /* -------------------------------------------------------------- 弹窗 */

    /*
     * 弹窗界面全部在 `ui/Dialogs.kt`，这里只把「要显示哪一个」写进 [State.dialog]，
     * 需要 Context 的那两个（服务器地址）留在本类。
     */

    /** 设置 → 杂项：服务器地址。 */
    fun showServerDialog() {
        State.dialog = AppDialog.Server
    }

    /** 服务器地址弹窗的「保存并导入」。 */
    fun saveServerAddress(text: String) {
        State.saveServer(this, text)
        AppCore.closeOverlay()
        toast("正在连接 ${State.server} …")
        importFromServer()
    }

    /** 服务器地址弹窗的「离线演示」。 */
    fun goOfflineDemo() {
        State.online = false
        State.loadError = "离线模式：请在「设置 → 杂项」填写服务器地址"
        AppCore.closeOverlay()
        State.data = null
    }

    /* ------------------------------------------------------ 拍照识别 */

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!photos.onActivityResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /* ------------------------------------------------------ 小工具 */

    fun toast(message: String) = AppCore.toast(message)
}
