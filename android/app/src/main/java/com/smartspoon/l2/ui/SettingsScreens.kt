package com.smartspoon.l2.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspoon.l2.Device
import com.smartspoon.l2.Food
import com.smartspoon.l2.MainActivity
import com.smartspoon.l2.State

/*
 * 设置区的五个页面（p.14~p.18）：设置首页 / 设备管理 / 菜单 / 杂项 / 账户设置。
 *
 * 这一版按 Gramophone 的设置页**逐项对齐组件构成**（数值取自它的布局 XML）：
 * - 行一律用 [PrefRow]：透明底、24dp 内边距、24dp 图标（末尾再留 24dp）、
 *   标题 19sp、副标题 15sp、**没有分隔线也没有尾部箭头**（它整行可点，反馈靠水波）；
 *   分组用 [PrefCategory]（24dp 内边距 + 12dp 上边距、primary 色、字重 600）。
 * - 开关行用 [PrefSwitchRow]（右侧是一整颗 MD3 开关，文字列与开关间留 16dp）。
 * - 「值」行右侧用 [PrefDropdown]：它那里是一个 `Spinner`（当前值 + 小箭头、无背景），
 *   所以不再用胶囊，只留文字 + 箭头 + `DropdownMenu`。
 * - 行与行之间不再包卡片、不再给图标套圆角色块——Gramophone 的设置页就是一列平铺的行。
 *
 * 页面跳转的动画由 App.kt 统一负责（交叉淡入，时长与它的 Activity 转场实测值一致），
 * 页面自己不再画标题和返回入口。
 */

/* ------------------------------------------------------------ p.14 设置首页 */

/**
 * 设置首页：账户行 + 设备管理 / 菜单 / 杂项三个入口，每行「图标 + 标题 + 说明」。
 *
 * 四个入口都用 `startActivity` 打开**独立 Activity**（和 Gramophone 一样），
 * 所以这一页自己不需要任何导航状态——返回由 Activity 返回栈负责。
 */
@Composable
fun SettingsScreen(a: MainActivity) {
    val user = State.data?.user?.name ?: "张三"
    val connected = State.connectedDevice()?.name ?: "无"
    val context = LocalContext.current

    // 只有四行、不满一屏，用 BounceColumn 才能拖出回弹（和用餐记录一致）
    BounceColumn(Modifier.fillMaxSize()) {
        PrefRow(
            icon = Icons.Filled.Person,
            title = user,
            summary = "账户设置",
            onClick = { context.startActivity(Intent(context, AccountSettingsActivity::class.java)) },
        )
        PrefRow(
            icon = Icons.Filled.Bluetooth,
            title = "设备管理",
            summary = "当前已连接: $connected",
            onClick = { context.startActivity(Intent(context, DeviceSettingsActivity::class.java)) },
        )
        PrefRow(
            icon = Icons.Filled.MenuBook,
            title = "菜单",
            summary = "添加菜单、修改菜单中的食物",
            onClick = { context.startActivity(Intent(context, MenuManageActivity::class.java)) },
        )
        PrefRow(
            icon = Icons.Filled.Tune,
            title = "杂项",
            summary = "单位、时间显示、服务器地址",
            onClick = { context.startActivity(Intent(context, MiscSettingsActivity::class.java)) },
        )
    }
}

/* -------------------------------------------------------- p.15 设备管理 */

/**
 * 设备管理：当前连接 + 自动连接开关 + 作用域 + 设备列表。
 *
 * 设备的「保存 / 取消保存」和「连接 / 断开连接」直接改 [Device.saved] 与 [State.connectedId]；
 * 这两个字段都是 Compose 可观察的，改完界面自己就变了。
 */
@Composable
fun DeviceSettingsScreen(a: SettingsHost) {
    val devices = (State.data?.devices ?: emptyList()).filter {
        if (State.deviceScope == "nearby") it.nearby else it.saved
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            PrefRow(
                icon = Icons.Filled.Bluetooth,
                title = "当前已连接",
                summary = State.connectedDevice()?.name ?: "无",
            )
        }

        item {
            PrefSwitchRow(
                icon = Icons.Filled.Tune,
                title = "自动连接",
                summary = "自动尝试连接已保存的智味勺",
                checked = State.autoConnect,
            ) { State.autoConnect = it }
        }

        // 作用域：对应旧版右对齐的 selectBox（已保存的智味勺 / 附近的智味勺）
        item {
            PrefRow(
                icon = Icons.Filled.Person,
                title = "设备范围",
                summary = "在哪些智味勺里查找",
                trailing = {
                    PrefDropdown(
                        value = if (State.deviceScope == "nearby") "附近的智味勺" else "已保存的智味勺",
                        options = listOf("已保存的智味勺", "附近的智味勺"),
                    ) { picked ->
                        State.deviceScope = if (picked == "附近的智味勺") "nearby" else "saved"
                    }
                },
            )
        }

        if (devices.isEmpty()) {
            item {
                Text(
                    "没有设备，切换到「附近的智味勺」并保存",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                )
            }
        } else {
            item { PrefCategory("设备") }
        }

        items(devices, key = { it.id }) { device -> DeviceRow(a, device) }
    }
}

/** 设备列表的一行：名称 + 电量 + 保存 / 连接两个动作。 */
@Composable
private fun DeviceRow(a: SettingsHost, device: Device) {
    val connected = device.id == State.connectedId

    PrefRow(
        icon = Icons.Filled.Bluetooth,
        title = device.name,
        summary = "剩余电量: ${device.battery}%",
        trailing = {
            // 两个按钮竖着排：MD3 按钮比旧版胶囊按钮宽得多，并排放会把设备名挤成「张三…」，
            // 竖排后名字能完整显示。
            Column(horizontalAlignment = Alignment.End) {
                PillButton(if (device.saved) "取消保存" else "保存") {
                    device.saved = !device.saved
                    a.toast(if (device.saved) "已保存" else "已取消保存")
                    a.loadFromStore()
                }
                Spacer(Modifier.height(4.dp))
                PillButton(if (connected) "断开连接" else "连接", filled = !connected) {
                    State.connectedId = if (connected) null else device.id
                    a.toast(if (connected) "已断开连接" else "已连接")
                    a.loadFromStore()
                }
            }
        },
    )
}

/* ------------------------------------------------------------ p.16 菜单 */

/**
 * 菜单管理：每份菜单一行，下面按行列出它包含的食物。
 *
 * 食物的缩略行缩进到菜单名文字的位置（24dp 内边距 + 24dp 图标 + 24dp 间距 = 72dp）。
 */
@Composable
fun MenuManageScreen(a: SettingsHost) {
    val menus = State.data?.menus ?: emptyList()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (menus.isNotEmpty()) item { PrefCategory("本餐菜单") }

        items(menus, key = { it.id }) { menu ->
            val foods = menu.foods.mapNotNull { State.food(it) }
            Column {
                PrefRow(
                    icon = Icons.Filled.MenuBook,
                    title = menu.name,
                    summary = "${foods.size} 种食物",
                )
                foods.forEach { food -> MenuFoodLine(food) }
            }
        }

        // 旧版是一个居中的「＋」，点它只弹提示（原型里还不支持新增菜单）
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledTonalIconButton(onClick = { a.toast("原型演示：暂不支持新增菜单") }) {
                    Icon(Icons.Filled.Add, contentDescription = "新增菜单")
                }
            }
        }
    }
}

/** 菜单里的一道菜：emoji + 名称，缩进到 [PrefRow] 的文字起始位置。 */
@Composable
private fun MenuFoodLine(food: Food) {
    // 24dp 内边距 + 24dp 图标 + 24dp 图标末尾间距
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 72.dp, end = 24.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(State.emojiFor(food), fontSize = 15.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            food.name,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ------------------------------------------------------------ p.17 杂项 */

/** 杂项：时间显示的两个开关、热量/重量单位、服务器地址与导入。 */
@Composable
fun MiscScreen(a: SettingsHost) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { PrefCategory("时间显示") }

        item {
            PrefSwitchRow(
                icon = Icons.Filled.Info,
                title = "时间显示年",
                summary = "在显示时间的位置显示该时间点对应的年份",
                checked = State.showYear,
            ) {
                State.showYear = it
                a.persistSettings()
            }
        }

        item {
            PrefSwitchRow(
                icon = Icons.Filled.Info,
                title = "时间显示秒",
                summary = "在显示时间的位置显示该时间点对应的秒数",
                checked = State.showSecond,
            ) {
                State.showSecond = it
                a.persistSettings()
            }
        }

        item { PrefCategory("单位") }

        item {
            PrefRow(
                icon = Icons.Filled.Tune,
                title = "热量单位",
                summary = "更改食物热量显示的单位",
                trailing = {
                    PrefDropdown(State.energyUnit, listOf("kJ", "kcal")) {
                        State.energyUnit = it
                        a.persistSettings()
                    }
                },
            )
        }

        item {
            PrefRow(
                icon = Icons.Filled.Tune,
                title = "重量单位",
                summary = "更改食物重量显示的单位",
                trailing = {
                    PrefDropdown(State.weightUnit, listOf("g", "kg", "两")) {
                        State.weightUnit = it
                        a.persistSettings()
                    }
                },
            )
        }

        item { PrefCategory("服务器") }

        item {
            PrefRow(
                icon = Icons.Filled.Bluetooth,
                title = "服务器地址",
                summary = State.server,
                onClick = { a.showServerDialog() },
                trailing = { OnlineTail() },
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton("修改地址") { a.showServerDialog() }
                Spacer(Modifier.width(8.dp))
                PillButton("从服务器导入菜品") { a.importFromServer() }
            }
        }
    }
}

/** 连接状态：旧版的 C.BRAND / C.DANGER，在 MD3 里对应 primary / error。 */
@Composable
private fun OnlineTail() {
    Text(
        if (State.online) "已连接" else "未连接",
        fontSize = 15.sp,
        color = if (State.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/* -------------------------------------------------------- p.18 账户设置 */

/** 账户设置：账号行 + 昵称 / 头像 / 数据导出 / 关于四行（只读，不额外发明交互）。 */
@Composable
fun AccountScreen(a: SettingsHost) {
    val name = State.data?.user?.name ?: "张三"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(name, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "智味勺用户 · L2",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { PrefCategory("账户") }

        item {
            PrefRow(Icons.Filled.Edit, "昵称", "修改在应用中显示的名字", trailing = { ValueTail(name) })
        }
        item { PrefRow(Icons.Filled.Image, "头像", "修改账户头像") }
        item {
            PrefRow(Icons.Filled.FileDownload, "数据导出", "导出用餐记录为文件", trailing = { ValueTail("CSV") })
        }
        item { PrefRow(Icons.Filled.Info, "关于智味勺", "版本 1.0.0（Compose + Material 3）") }
    }
}

/** 设置行右侧的次要文字（旧的 tailText）。 */
@Composable
private fun ValueTail(value: String) {
    Text(value, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
