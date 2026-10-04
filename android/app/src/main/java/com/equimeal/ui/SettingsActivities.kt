package com.equimeal.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.equimeal.AppCore
import com.equimeal.Bite
import com.equimeal.BlePermissions
import com.equimeal.BleSpoon
import com.equimeal.DialogActions
import com.equimeal.MealSession
import com.equimeal.SpoonLink
import com.equimeal.State

/*
 * 设置区的四个子页现在和 Gramophone 一样是**独立 Activity**：
 *
 * - 转场由系统负责（原生 Activity 转场），不再是 Compose 里模拟出来的；
 * - 每个子页是独立窗口，**整窗（标题栏 + 折叠行为 + 内容）一起转场**；
 * - 系统返回键由 Activity 返回栈处理（`finish()`），不需要应用内的 BackHandler。
 *
 * 设置首页仍在 MainActivity 的「设置」标签里，它只负责 startActivity 打开这四个子页。
 *
 * 外壳（大标题栏 + 返回箭头 + 内容）现在在 [BasePageActivity] 里，和用餐流程那四页共用；
 * 这些子页需要的那几个动作（toast / 持久化设置 / 重读本地库 / 从服务器导入 / 服务器弹窗）
 * 仍然定义在 [SettingsHost] 里，由本基类实现。
 */

/** 设置子页需要宿主提供的动作。 */
interface SettingsHost {
    fun toast(message: String)
    fun loadFromStore()
    fun persistSettings()
    fun showServerDialog()
    fun importFromServer()

    /*
     * 蓝牙（真机勺子）的三个动作：设置子页全都继承 [BasePageActivity]，
     * 而权限的注册必须发生在 Activity STARTED 之前，所以入口只能留在 Activity 上。
     */
    fun scanSpoons()
    fun connectSpoon(address: String, name: String)
    fun disconnectSpoon()

    /** 只申请蓝牙权限（设备页在权限没给时要给用户一个明确的按钮）。 */
    fun grantBlePermission()

    /**
     * 「权限 + 蓝牙开关」都就绪之后再做某事。
     *
     * 设备页点「连接」要的正是这个：**系统弹窗只能由当前 Activity 发起**，
     * 而各页面的 `BlePermissions` 是饿汉式建好的（只有那时注册才合法）。
     * 所以"要权限/开蓝牙"由页面提供，业务层（`BleSpoon`）只负责提出请求。
     */
    fun bleReady(onReady: () -> Unit)

    /** 设备行「点这一行改名」。 */
    fun showRenameDevice(deviceId: String)

    /** 「显示用餐提醒」开关（点「我已知晓」之后会自动关掉，可在设置里重新打开）。 */
    fun setShowMealRemind(show: Boolean)
}

/** 设置动作的共享实现（`MainActivity` 与设置子 Activity 用的是同一套键名与同一份逻辑）。 */
object SettingsActions {
    const val PREFS = "smartspoon"

    fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("energy_unit", State.energyUnit)
            .putString("weight_unit", State.weightUnit)
            .putBoolean("show_year", State.showYear)
            .putBoolean("show_second", State.showSecond)
            .putBoolean("auto_connect", State.autoConnect)
            // 用餐提醒开关也在这里落盘（State.setShowMealRemind 会单独写一次，
            // 这一行是为了让"设置里改完顺手 persist 一次"的既有约定保持完整）
            .putBoolean("show_meal_remind", State.showMealRemind)
            .apply()
    }
}

/**
 * 设置子页的基类：一页 = 大标题栏（返回箭头 = `finish()`）+ 子类给的页面内容。
 *
 * 外壳本身已提到 [BasePageActivity]，四个子页各自只声明标题与内容。
 */
abstract class BaseSettingsActivity : BasePageActivity(), SettingsHost, DialogActions {

    private val serverDialogOpen = mutableStateOf(false)

    /** 蓝牙权限（连接真机勺子）。必须饿汉式，理由见 [BlePermissions]。 */
    private val ble = BlePermissions(this)

    /**
     * 设置子页的弹窗。
     *
     * 除了这一页自带的「服务器地址」弹窗，还必须挂上全局的 [AppDialogs] ——
     * 设备管理页的「修改勺子名称」等弹窗就是通过 `State.dialog` 走的这条路。
     *
     * 这个疏漏踩过一次：`DeviceRow` 点了以后 `State.dialog` 确实被设上了，
     * 但**没有任何 composable 去画它**，表现就是"点了行完全没反应"。
     * 基类只挂自己那一个弹窗时，这类"功能看着没实装"的 bug 会一直藏着。
     */
    @Composable
    override fun Overlays() {
        AppDialogs(this)
        if (serverDialogOpen.value) ServerAddressDialog()
    }

    /* ------------------------------------------------------- DialogActions */

    override fun closeOverlay() = AppCore.closeOverlay()

    override fun showConnectDialog(picking: Boolean) = AppCore.showConnectDialog(picking)

    override fun selectConnectedDevice(deviceId: String) = AppCore.selectConnectedDevice(deviceId)

    override fun zeroSpoonWeight() = AppCore.zeroSpoonWeight()

    override fun scanSpoons() = ble.ensureReady { SpoonLink.startScan() }

    /**
     * 连接一台勺子。
     *
     * 这里**两层都要有**：外层 `ble.ensureReady` 是本页自己的权限/开蓝牙入口；
     * 内层把同一个能力交给 [BleSpoon.connect]，好让它在"用户点了连接但环境没就绪"时
     * 能请本页去要权限（而不是自己去 new 一个 BlePermissions —— 那样在已 RESUMED 的
     * 页面上会抛异常，表现就是"点连接没反应"）。
     */
    override fun connectSpoon(address: String, name: String) =
        ble.ensureReady {
            BleSpoon.connect(this, BleSpoon.BleReady { ready -> ble.ensureReady(ready) }, address, name)
        }

    override fun disconnectSpoon() = BleSpoon.disconnect()

    override fun grantBlePermission() = ble.ensure { }

    override fun bleReady(onReady: () -> Unit) = ble.ensureReady(onReady)

    override fun showRenameDevice(deviceId: String) = AppCore.showRenameDevice(deviceId)

    override fun renameDevice(deviceId: String, name: String): String =
        AppCore.renameDevice(deviceId, name).also { toast(it) }

    /** 用餐提醒只由用餐流程那四个 Activity 弹出，设置页上这一项不可达。 */
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

    override fun saveServerAddress(text: String) {
        State.saveServer(this, text)
        closeOverlay()
        toast("正在连接 ${State.server} …")
        importFromServer()
    }

    override fun goOfflineDemo() {
        State.online = false
        State.loadError = "离线模式：请在「设置 → 杂项」填写服务器地址"
        closeOverlay()
        State.data = null
    }

    override fun takePhoto() = toast("请到「快速开始 → 自定义本餐菜单」里拍照识别")

    override fun pickImage() = toast("请到「快速开始 → 自定义本餐菜单」里选择图片")

    /** 服务器地址弹窗：写回 [State.server] 并触发一次导入。 */
    @Composable
    private fun ServerAddressDialog() {
        val text = androidx.compose.runtime.remember { mutableStateOf(State.server) }
        AlertDialog(
            onDismissRequest = { serverDialogOpen.value = false },
            title = { Text("服务器地址") },
            text = {
                Column {
                    Text(
                        "应用启动时从这个地址读取菜品信息。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = text.value,
                        onValueChange = { text.value = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    serverDialogOpen.value = false
                    State.saveServer(this, text.value)
                    toast("正在连接 ${State.server} …")
                    importFromServer()
                }) { Text("保存并导入") }
            },
            dismissButton = {
                TextButton(onClick = { serverDialogOpen.value = false }) { Text("取消") }
            },
        )
    }

    /* ------------------------------------------------------- SettingsHost */

    override fun toast(message: String) = AppCore.toast(message)

    override fun loadFromStore() = AppCore.loadFromStore()

    override fun persistSettings() = SettingsActions.persist(this)

    override fun importFromServer() = AppCore.importFromServer { message -> toast(message) }

    override fun showServerDialog() {
        serverDialogOpen.value = true
    }

    override fun setShowMealRemind(show: Boolean) {
        State.setShowMealRemind(this, show)
        toast(if (show) "开始用餐前会显示提醒" else "开始用餐时直接进入用餐中")
    }
}

/* --------------------------------------------------------------- 四个子页 */

class DeviceSettingsActivity : BaseSettingsActivity() {
    override val pageTitle = "设备管理"

    @Composable
    override fun Page() {
        DeviceSettingsScreen(this)
    }
}

class MenuManageActivity : BaseSettingsActivity() {
    override val pageTitle = "菜单"

    @Composable
    override fun Page() {
        MenuManageScreen(this)
    }

    override fun onResume() {
        super.onResume()
        // 菜单管理页列的是菜品与菜单：进来时对齐一次服务器（菜品库是所有账号共用的一份）
        AppCore.syncDishesFromServer("菜单管理")
    }
}

class MiscSettingsActivity : BaseSettingsActivity() {
    override val pageTitle = "杂项"

    @Composable
    override fun Page() {
        MiscScreen(this)
    }
}

/**
 * 账户设置（p.18）。
 *
 * 它是唯一一个需要 [AccountHost]（而不只是 [SettingsHost]）的设置子页：
 * 未登录时它显示登录/注册表单，登录后显示账号信息。两个界面都在 [AccountScreens.kt]。
 */
class AccountSettingsActivity : BaseSettingsActivity(), AccountHost {
    override val pageTitle = "账户设置"

    @Composable
    override fun Page() {
        AccountScreen(this)
    }

    /* ------------------------------------------------------- AccountHost */

    override fun authenticate(
        username: String,
        password: String,
        nickname: String,
        register: Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        AppCore.authenticate(this, username, password, nickname, register) { error ->
            if (error == null) {
                toast("已登录：${State.account?.displayName ?: username}")
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    override fun logout() {
        AppCore.logout(this)
        toast("已退出登录")
    }

    override fun renameAccount(name: String, onDone: () -> Unit) {
        AppCore.renameAccount(this, name) { message ->
            toast(message)
            onDone()
        }
    }

    /**
     * 「暂不登录，继续离线使用」：直接关掉这一页回到设置。
     *
     * 明确做成"能退出"而不是"必须登录"，因为菜品库、菜单、用餐记录全在本地库里，
     * 未登录也能完整使用。
     */
    override fun skipLogin() {
        finish()
    }
}

/**
 * 统计数据（设计稿第 13 页）。
 *
 * 它不属于设置区，但用的是同一个「一页 = 大标题栏 + 内容」的壳，所以放在这里复用基类。
 * 第 12 页那种汇总样式现在在用餐记录列表底部就地展开，不再靠圆点切页。
 */
class StatsActivity : BaseSettingsActivity() {
    override val pageTitle = "统计数据"

    @Composable
    override fun Page() {
        StatsScreen(this)
    }
}


/** 第 12 页：统计数据汇总（「关于手机」式列表），入口在第 13 页底部。 */
class StatsSummaryActivity : BaseSettingsActivity() {
    override val pageTitle = "统计数据汇总"

    @Composable
    override fun Page() {
        StatsSummaryScreen()
    }
}
