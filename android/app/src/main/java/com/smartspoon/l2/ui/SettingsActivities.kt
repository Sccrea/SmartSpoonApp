package com.smartspoon.l2.ui

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
import com.smartspoon.l2.AppCore
import com.smartspoon.l2.State

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
            .apply()
    }
}

/**
 * 设置子页的基类：一页 = 大标题栏（返回箭头 = `finish()`）+ 子类给的页面内容。
 *
 * 外壳本身已提到 [BasePageActivity]，四个子页各自只声明标题与内容。
 */
abstract class BaseSettingsActivity : BasePageActivity(), SettingsHost {

    private val serverDialogOpen = mutableStateOf(false)

    @Composable
    override fun Overlays() {
        if (serverDialogOpen.value) ServerAddressDialog()
    }

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
}

class MiscSettingsActivity : BaseSettingsActivity() {
    override val pageTitle = "杂项"

    @Composable
    override fun Page() {
        MiscScreen(this)
    }
}

class AccountSettingsActivity : BaseSettingsActivity() {
    override val pageTitle = "账户设置"

    @Composable
    override fun Page() {
        AccountScreen(this)
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
