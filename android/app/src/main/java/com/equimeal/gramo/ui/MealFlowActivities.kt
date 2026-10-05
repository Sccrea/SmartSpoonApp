package com.equimeal.gramo.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.equimeal.gramo.AppCore
import com.equimeal.gramo.AppDialog
import com.equimeal.gramo.Bite
import com.equimeal.gramo.BlePermissions
import com.equimeal.gramo.BleSpoon
import com.equimeal.gramo.DialogActions
import com.equimeal.gramo.Food
import com.equimeal.gramo.MainActivity
import com.equimeal.gramo.MealSession
import com.equimeal.gramo.MenuDef
import com.equimeal.gramo.PhotoRecognizer
import com.equimeal.gramo.Screen
import com.equimeal.gramo.SpoonLink
import com.equimeal.gramo.State

/*
 * 用餐流程的四个页面 —— 阶段 3：它们和「设置子页」「统计数据」一样改成**独立 Activity**。
 *
 * 为什么：原来这四个页面是 `MainActivity` 里的 `State.screen` 分支，转场是 Compose 里
 * 模拟出来的（`AnimatedContent` + 手工算方向 + 手工压暗旧页）。改成独立 Activity 之后
 * 转场交给系统，整窗（标题栏 + 折叠行为 + 内容）一起动，和 Gramophone 的设置子页一模一样。
 *
 * 分工：
 * - [MealFlowHost]：四个屏幕需要宿主提供的那批动作（= 加菜 / 用餐中 / 结果页真正调用的那批）；
 * - [BaseMealActivity]：这批动作的**唯一一份实现**，四个 Activity 直接继承；
 * - 四个具体 Activity 只声明标题、内容，以及「返回键该怎么走」。
 *
 * 会话状态（[MealSession]）与三个挂钩（[AppCore]）都是进程级的，所以「谁在前台」不影响记录。
 */

/** 用餐流程四个屏幕需要宿主提供的能力。 */
interface MealFlowHost {

    fun toast(message: String)

    /* 选择本餐菜单 */
    fun pickMenu(menu: MenuDef)

    /* 自定义本餐菜单（加菜页） */
    fun toggleFood(id: String)
    fun handleDishAction(food: Food, action: DishAction)
    fun openRecognizeDialog()
    fun showDishEditor(food: Food?)

    /**
     * 从服务器把菜品库拉回本地（加菜页空状态里的那个按钮）。
     *
     * 放在这个接口上，是因为"菜品库是空的"这件事**最先被用户看见的地方就是加菜页** ——
     * 用户站在那里需要一个就在手边的入口，而不是切到设置里去找。
     * （「设置 → 杂项」里那颗「从服务器导入菜品」按钮已经去掉了：导入的是菜品，
     * 这个入口留在菜品相关的地方更合理。）
     */
    fun importDishesFromServer()
    fun finishSelecting()

    /* 用餐中 */
    fun chooseSpoonFood(food: Food)
    fun addFoodDuringMeal()
    // 这里原来有 `recordBite()`：给界面上的「记录一口」按钮用。
    // 那个按钮删掉之后它就没有调用者了，实现（MealSession.recordBite）也已整段移除 ——
    // 记口现在只由勺子判定嘴接触后推 `B` 帧发起。
    fun exitMeal()
    fun finishMeal()

    /* 用餐结果 */
    fun showBiteDetail()
    fun editResultValue(field: String)

    /** 结果页点「本餐菜单」那一行：改这一餐的名字（两种用餐入口都能改）。 */
    fun editMealName()
    fun doneResult()

    /** 四个页面里都可能弹窗（连接智味勺 / 用餐提醒 / 识别结果 / 修正数据…）。 */
    val dialogs: DialogActions
}

/**
 * 用餐流程四个页面的公共基类：外壳来自 [BasePageActivity]，业务动作实现自 [MealFlowHost]
 * 与 [DialogActions]。**这份实现是唯一的**，`MainActivity` 里对应的方法全部改成转发到
 * [AppCore]（或直接删除），两边不会各写一半。
 */
abstract class BaseMealActivity : BasePageActivity(), MealFlowHost, DialogActions {

    /*
     * 拍照 / 选图 / 上传识别。
     *
     * 必须是**饿汉式**的字段初始化，**不能写成 `by lazy`**：[PhotoRecognizer] 的构造函数里
     * 要调 `registerForActivityResult`，而它只允许在 Activity STARTED 之前注册。
     * 字段初始化发生在构造期（早于 onCreate），合法；换成 by lazy 会推迟到第一次点
     * 「拍照 / 选择图片」时才注册，那时 Activity 已经是 RESUMED —— 直接抛
     * `IllegalStateException: ... is attempting to register while current state is RESUMED`，
     * 表现就是一点就闪退。
     */
    private val photos = PhotoRecognizer(this)

    /** 蓝牙权限（连接真机勺子）。同样必须饿汉式，理由见 [BlePermissions]。 */
    private val ble = BlePermissions(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 新进来的一页不该继承上一个 Activity 留下的弹窗
        State.dialog = null
        super.onCreate(savedInstanceState)
    }

    override val dialogs: DialogActions get() = this

    @Composable
    override fun Overlays() {
        AppDialogs(this)
    }

    /* --------------------------------------------------------- MealFlowHost */

    override fun toast(message: String) = AppCore.toast(message)

    override fun pickMenu(menu: MenuDef) = AppCore.pickMenu(menu)

    override fun toggleFood(id: String) {
        AppCore.toggleFood(id)
    }

    override fun handleDishAction(food: Food, action: DishAction) {
        when (action) {
            DishAction.ToggleFavorite -> toast(AppCore.toggleFavorite(food))
            DishAction.Edit -> showDishEditor(food)
            DishAction.Delete -> AppCore.confirmDeleteDish(food)
        }
    }

    override fun openRecognizeDialog() = AppCore.openRecognizeDialog()

    override fun importDishesFromServer() {
        AppCore.importFromServer { message -> toast(message) }
    }

    override fun showDishEditor(food: Food?) = AppCore.showDishEditor(food)

    /**
     * 托盘上的「选好了 / 完成添加」。
     *
     * ## 没连勺子时不再显示"当前已连接：未连接"
     *
     * 而是**直接跳到"连接智味勺"那个选择列表**（就是点「重新选择」看到的界面）。
     * 理由：没勺子就没法记录，让用户停在一个写着"未连接"的确认页上，他除了点「重新选择」
     * 没有别的可做 —— 那就别让他多点这一下，直接把列表摆出来。
     */
    override fun finishSelecting() {
        // 从「用餐中」的「＋ 添加食物」进来的：这一下是「加完了」，
        // 该回到用餐中并恢复记录，而不是弹出连接勺子（那会另起一餐）。
        if (State.addingFood) {
            finishAddingFood()
            return
        }
        if (State.selected.isEmpty()) {
            toast("请先添加至少一种食物")
            return
        }
        MealSession.currentMealName = MealSession.DEFAULT_CUSTOM_MEAL_NAME
        if (SpoonLink.ready) {
            AppCore.showConnectDialog(picking = false)
        } else {
            // 还没连勺子：直接进"选一台勺子连上"的列表，并说明为什么要先连
            toast("请先连接智味勺：连上之后才能开始记录")
            AppCore.showConnectDialog(picking = true)
        }
    }

    override fun chooseSpoonFood(food: Food) = MealSession.chooseSpoonFood(food)

    /** 「＋ 添加食物」：暂停记录，然后**打开加菜页**（这一步原来是改 State.screen）。 */
    override fun addFoodDuringMeal() {
        MealSession.addFoodDuringMeal()
        startActivity(Intent(this, FoodPickerActivity::class.java))
    }

    /** 退出记录：停计时与轮询，然后清掉整条用餐流程回到主界面的「快速开始」。 */
    override fun exitMeal() {
        MealSession.exitMeal()
        backToMain(tab = 0, quickStart = true)
    }

    /** 结束用餐：落库这一餐，然后打开**独立的**用餐结果页。 */
    override fun finishMeal() {
        MealSession.finishMeal()
        startActivity(Intent(this, ResultActivity::class.java))
        finish()
    }

    override fun showBiteDetail() = AppCore.showBiteDetail()

    override fun editResultValue(field: String) = AppCore.editResultValue(field)

    /** 结果页「本餐菜单」那一行：挂上改名弹窗（弹窗里预填当前名字）。 */
    override fun editMealName() {
        State.dialog = AppDialog.MealName
    }

    /** 结果页「完成」：把修正写回记录、重读本地库，然后回主界面。 */
    override fun doneResult() {
        MealSession.doneResult()
        backToMain(tab = 0, quickStart = true)
    }

    /* -------------------------------------------------------- DialogActions */

    override fun closeOverlay() = AppCore.closeOverlay()

    override fun showConnectDialog(picking: Boolean) = AppCore.showConnectDialog(picking)

    override fun selectConnectedDevice(deviceId: String) = AppCore.selectConnectedDevice(deviceId)

    override fun zeroSpoonWeight() = AppCore.zeroSpoonWeight()

    /* -------------------------------------------------- 真机蓝牙（BLE） */

    override fun scanSpoons() = ble.ensureReady { SpoonLink.startScan() }

    override fun connectSpoon(address: String, name: String) =
        ble.ensureReady {
            // 内层把本页的权限/开蓝牙能力交给 BleSpoon（理由见 SettingsActivities 里的同名方法）
            BleSpoon.connect(this, BleSpoon.BleReady { ready -> ble.ensureReady(ready) }, address, name)
        }

    override fun disconnectSpoon() = BleSpoon.disconnect()

    override fun grantBlePermission() = ble.ensure { }

    /**
     * 「权限 + 蓝牙开关」都就绪之后再做某事。
     *
     * 放在这个基类上，四个用餐页就都具备了 —— 系统弹窗必须由当前 Activity 发起，
     * 所以只有页面自己拿着那个饿汉式建好的 [BlePermissions] 才能做这件事。
     */
    override fun bleReady(onReady: () -> Unit) = ble.ensureReady(onReady)

    override fun showRenameDevice(deviceId: String) = AppCore.showRenameDevice(deviceId)

    override fun renameDevice(deviceId: String, name: String): String =
        AppCore.renameDevice(deviceId, name).also { toast(it) }

    /**
     * 用餐提醒弹窗的「我已知晓」。
     *
     * 点过这一次之后就不该再打扰用户：关掉提醒开关（落偏好），再进入「用餐中」。
     * 想重新看到提醒可以在「设置 → 设备管理 → 用餐提醒」里打开。
     */
    override fun confirmRemindAndStart() {
        State.setShowMealRemind(this, false)
        enterLive()
    }

    /**
     * 连接弹窗的「开始用餐」。
     *
     * [AppCore.startMeal] 会按「用餐提醒」开关决定要不要挂提醒弹窗：
     * - 挂上了（`dialog != null`）→ 停在这一页等用户点「我已知晓」；
     * - 没挂（用户已经关掉提醒）→ **直接进「用餐中」**，不再多按一次。
     */
    override fun startMeal() {
        AppCore.startMeal()
        if (State.dialog == null) enterLive()
    }

    /** 真正进入「用餐中」：重置计时、开始收数，然后打开 LiveActivity。 */
    private fun enterLive() {
        closeOverlay()
        State.meal.startedAt = System.currentTimeMillis()
        MealSession.startTicker()
        MealSession.startPolling()
        startActivity(Intent(this, LiveActivity::class.java))
    }

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

    override fun saveDishToServer(name: String, calorie: Double) =
        AppCore.saveDishToServer(name, calorie) { toast(it) }

    override fun saveServerAddress(text: String) {
        State.saveServer(this, text)
        closeOverlay()
        toast("正在连接 ${State.server} …")
        AppCore.importFromServer { toast(it) }
    }

    override fun goOfflineDemo() {
        State.online = false
        State.loadError = "离线模式：请在「设置 → 杂项」点「服务器地址」填写"
        closeOverlay()
        State.data = null
    }

    override fun takePhoto() = photos.takePhoto()

    override fun pickImage() = photos.pickImage()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!photos.onActivityResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /* ------------------------------------------------------------ 加菜页要用 */

    /**
     * 加菜页的「返回」=「加完了」：把暂停时长扣掉、恢复记录与轮询，然后关掉这一页
     * （「用餐中」那一页还在返回栈里，直接就回来了）。
     */
    protected fun finishAddingFood() {
        MealSession.finishAddingFood()
        finish()
    }

    /**
     * 回到主界面的某个标签，并把整条用餐流程的返回栈清掉。
     *
     * `CLEAR_TOP` 会先把主界面之上的「选择本餐菜单 / 用餐中 / 用餐结果」全部结束掉，
     * 再由已经存在的 `MainActivity`（singleTop）接住这次 Intent —— 于是「完成」之后
     * 按系统返回不会又退回用餐结果页。
     */
    protected fun backToMain(tab: Int, quickStart: Boolean) {
        State.tab = tab
        State.screen = if (quickStart) Screen.QuickStart else Screen.Tab
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}

/* -------------------------------------------------------------- 四个页面 */

/** p.02 选择本餐菜单。 */
class MenuChooserActivity : BaseMealActivity() {
    override val pageTitle = "选择本餐菜单"

    @Composable
    override fun Page() {
        MenuChooserScreen(this)
    }
}

/**
 * p.03 自定义本餐菜单（也是「用餐中 → ＋ 添加食物」进来的加菜页）。
 *
 * 它同时承担两件事，靠 [State.addingFood] 区分：托盘按钮的文字、「返回」的含义、
 * 以及步骤条停在第几步都跟着它变。
 */
class FoodPickerActivity : BaseMealActivity() {
    override val pageTitle = "自定义本餐菜单"

    /** 这一页钉着「步骤条 + 搜索 + 分类 + 排序」，外加底部托盘，只能用小标题栏。 */
    override val compactBar = true

    @Composable
    override fun Page() {
        FoodPickerScreen(this)
    }

    override fun onResume() {
        super.onResume()
        // 加菜页属于用餐流程：确保"菜单编辑"的标记没有残留（否则步骤条不画）
        State.editingMenu = false
        // 列菜品时对齐服务器：这一页是"菜品库"最常被看到的地方
        AppCore.syncDishesFromServer("加菜页")
    }

    /** 托盘从 `App.kt` 的外层 Scaffold 搬到了这一页自己身上（它只属于这一页）。 */
    @Composable
    override fun BottomBar() {
        TrayBar(this)
    }

    override fun goBack() {
        if (State.addingFood) {
            finishAddingFood()
            return
        }
        super.goBack()
    }
}

/**
 * p.07 用餐中。
 *
 * 这一页没有上一级，所以没有返回箭头；系统返回键也**不**把这一页关掉
 * （关掉的话「用餐中」就再也回不来了），而是把整个任务压到后台 ——
 * 和旧版「返回 = 退出到桌面、重新点开还停在用餐中」的观感一致。
 */
class LiveActivity : BaseMealActivity() {
    override val pageTitle = "用餐中"
    override val showBackArrow = false

    @Composable
    override fun Page() {
        LiveScreen(this)
    }

    override fun goBack() {
        moveTaskToBack(true)
    }
}

/** p.08 用餐结果。返回 = 回主界面的「用餐记录」标签（旧版 `navigateBack` 的 Result 分支）。 */
class ResultActivity : BaseMealActivity() {
    override val pageTitle = "用餐结果"

    @Composable
    override fun Page() {
        ResultScreen(this)
    }

    override fun goBack() {
        backToMain(tab = 2, quickStart = false)
    }
}

/* ------------------------------------------------------------ 底部托盘 */

/**
 * 已选食物 + 「选好了 / 完成添加」。
 *
 * 这是设计稿独有的结构，按你的要求保留；形式改成贴底的 MD3 面板。
 *
 * 已选食物**收起时只占一行**（「已选 N 种食物」+ 箭头），点开才展开成逐条列表；
 * 展开后高度也有上限、内部可滚动。原来是一行两个 chip 平铺，选得多了托盘会越长越高，
 * 把上面的选菜页顶出屏幕——所以改成现在这样。
 */
@Composable
fun TrayBar(a: MealFlowHost) {
    val chosen = State.selected.mapNotNull { State.food(it) }
    var expanded by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                if (chosen.isEmpty()) {
                    Text(
                        "还没有选择食物，点击「添加到菜单」加入本餐",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已选 ${chosen.size} 种食物",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "收起已选食物" else "展开已选食物",
                        )
                    }

                    if (expanded) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            chosen.forEach { food ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${State.emojiFor(food)}  ${food.name}",
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { a.toggleFood(food.id) }) {
                                        Text("⊗", fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { a.finishSelecting() }) {
                Text(if (State.addingFood) "完成添加" else "选好了")
            }
        }
    }
}
