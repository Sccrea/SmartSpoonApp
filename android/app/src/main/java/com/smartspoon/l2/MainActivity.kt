package com.smartspoon.l2

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.smartspoon.l2.ui.DishAction
import com.smartspoon.l2.ui.FoodPickerActivity
import com.smartspoon.l2.ui.MenuChooserActivity
import com.smartspoon.l2.ui.SmartSpoonApp
import com.smartspoon.l2.ui.SmartSpoonTheme

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
    fun openMenuChooser() {
        startActivity(Intent(this, MenuChooserActivity::class.java))
    }

    /** 快速开始 →「自定义本餐菜单」（p.03 食物列表）。 */
    fun openFoodPicker() {
        State.query = ""
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

    /** 用餐记录详情：列出这一餐的每一口（真实数据）。 */
    fun showMealDetail(record: MealRecord) = AppCore.showMealDetail(record)

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
