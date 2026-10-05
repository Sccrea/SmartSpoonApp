package com.equimeal.gramo.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.equimeal.gramo.BleSpoon
import com.equimeal.gramo.BuildConfig
import com.equimeal.gramo.Device
import com.equimeal.gramo.Food
import com.equimeal.gramo.MainActivity
import com.equimeal.gramo.SpoonLink
import com.equimeal.gramo.State

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
    // 账户行显示**真实登录状态**：未登录时是"未登录（点这里登录/注册）"，
    // 不再拿服务器示例数据里的"张三"顶上去（那是模板数据，已去掉）。
    val account = State.account
    val connected = State.connectedDevice()?.name ?: "无"
    val context = LocalContext.current

    // 只有四行、不满一屏，用 BounceColumn 才能拖出回弹（和用餐记录一致）
    BounceColumn(Modifier.fillMaxSize()) {
        PrefRow(
            icon = Icons.Filled.Person,
            title = account?.displayName ?: "未登录",
            summary = if (account == null) {
                "登录 / 注册智味勺账号（也可以不登录，继续离线使用）"
            } else {
                "账户设置 · 用户名 ${account.username}"
            },
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
 * 设备管理：**已保存的智味勺**列表（点一行改名/连接）+ 当前连接状态。
 *
 * ## 这一页被有意收窄了
 *
 * 原来这里还有：设备范围切换（已保存 / 附近）、「扫描附近的智味勺」、「连接上次的勺子」、
 * 「自动连接」开关、「显示用餐提醒」开关。现在都不在这一页了：
 *
 * | 原来在这里 | 现在在哪 | 为什么 |
 * | --- | --- | --- |
 * | 扫描附近 / 附近与已保存切换 | **连接智味勺弹窗**（用餐流程里点「选好了」或「重新选择」） | 连勺子本来就只有那一个场景；设置里再放一份"扫描"会让人以为要来设置里连 |
 * | 连接上次的勺子 | 不需要了 —— **App 启动时自动连**（见 `BleSpoon.autoConnectIfRemembered`） | 手动点这一下的唯一作用就是"我不想自动连"，而自动连正是用户要的 |
 * | 自动连接开关 | 同样去掉了 | 启动自动连接改成**固定行为**，不再需要一个开关 |
 * | 显示用餐提醒 | 「设置 → 杂项」 | 它是"用餐流程怎么走"的偏好，和设备没关系 |
 *
 * 所以这一页现在只回答一个问题：**我保存过哪些勺子，它们现在怎么样**。
 *
 * 点任意一行 = 改这台勺子的名字（真机的名字存在偏好里，不会被广播名覆盖回去）。
 */
@Composable
fun DeviceSettingsScreen(a: SettingsHost) {
    val nearbyScope = State.deviceScope == "nearby"

    // 扫描结果是"活的"：补进 State.data.devices（界面其它地方读的还是同一份列表）
    val scans = BleSpoon.detectedSorted()
    val scannedIds = scans.map { it.address }.toSet()

    LaunchedEffect(scans.map { it.address to it.rssi }) {
        scans.forEach { BleSpoon.deviceRowFor(it) }
    }

    /*
     * 切到「附近的智味勺」就自动扫一次。
     *
     * 作用域不是纯筛选器，而是"看哪一类设备"的入口 —— 切过去就该看到结果，
     * 而不是还要再点一次扫描按钮。
     */
    LaunchedEffect(nearbyScope) {
        if (nearbyScope && !SpoonLink.scanning && !SpoonLink.ready) a.scanSpoons()
    }

    val all = State.data?.devices ?: emptyList()

    /*
     * 列表内容（只有真勺子）：
     * - 附近：蓝牙扫到的 + 当前连着的；
     * - 已保存：保存过的 + 当前连着的。
     *
     * 两种情况都把"当前连着的"算进去，否则扫描结果被清空的那一刻，
     * 已经连上的勺子会从列表里凭空消失（明明还连着）。
     */
    val devices = all.filter { device ->
        if (nearbyScope) {
            device.isBle && (device.id in scannedIds || device.id == State.connectedId)
        } else {
            device.isBle && (device.saved || device.id == State.connectedId)
        }
    }.distinctBy { it.id }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            PrefRow(
                icon = Icons.Filled.Bluetooth,
                title = "当前已连接",
                summary = when {
                    SpoonLink.ready -> "${SpoonLink.connectedName.ifBlank { "智味勺" }}（蓝牙）"
                    SpoonLink.status == SpoonLink.Status.CONNECTING -> SpoonLink.statusText
                    State.connectedDevice() != null -> State.connectedDevice()!!.name
                    else -> "无（没有连接智味勺，无法开始用餐）"
                },
            )
        }

        /*
         * 「启动时自动连接」这一行去掉了。
         *
         * 它本来就是一句**纯说明**（自动回连是固定行为，没有开关可点），
         * 而"当前已连接"那一行已经回答了用户真正想知道的事（现在连上没有）。
         * 留着只占一屏位置，还要用户读一段解释"为什么这里不能点"。
         */

        // 作用域：已保存的智味勺 / 附近的智味勺。整行可点，弹出两项选择。
        item {
            PrefSelectRow(
                icon = Icons.Filled.Person,
                title = "设备范围",
                summary = "在哪些智味勺里查找",
                value = if (nearbyScope) "附近的智味勺" else "已保存的智味勺",
                options = listOf("已保存的智味勺", "附近的智味勺"),
            ) { picked ->
                State.deviceScope = if (picked == "附近的智味勺") "nearby" else "saved"
            }
        }

        item { PrefCategory("蓝牙") }

        /*
         * 蓝牙这一行**只显示一颗按钮**：
         * - 连着勺子 → 「断开连接」；
         * - 没连 → 「扫描附近的智味勺」。
         *
         * 两颗不同时出现是有原因的：扫描会**先把当前连接断开**（`startScan` 里就是这么做的），
         * 所以"已连接"的时候给你一颗「扫描」，等于一颗会悄悄断线的按钮。
         * 而这两件事本来也不可能同时做，并排放着只会让人犹豫该点哪个。
         */
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (SpoonLink.ready) {
                    PillButton("断开连接") { a.disconnectSpoon() }
                } else {
                    PillButton(if (SpoonLink.scanning) "正在扫描…" else "扫描附近的智味勺") {
                        a.scanSpoons()
                    }
                }
            }
        }

        /*
         * 「勺子状态」**只有 debug 构建**才显示。
         *
         * 这一行是给开发/现场排查用的：GATT 状态文本、帧计数、信号 dBm ——
         * 判断"到底连上没有、数据流是否正常"时很有用，但对普通用户没有意义
         * （`-87 dBm` 这种数还会让人以为信号有问题）。发布版里去掉，界面更干净。
         *
         * 用 `BuildConfig.DEBUG` 而不是编译期删代码：两条分支都要能被编译到，
         * 这样 debug 装机排查时它一定在，不会因为构建类型不同而"代码里搜不到"。
         */
        if (BuildConfig.DEBUG && SpoonLink.ready) {
            item {
                PrefRow(
                    icon = Icons.Filled.Bluetooth,
                    title = "勺子状态",
                    summary = buildString {
                        append(SpoonLink.statusText)
                        if (SpoonLink.frames > 0) append(" · 已收 ${SpoonLink.frames} 帧")
                        if (SpoonLink.battery in 0..100) append(" · 电量 ${SpoonLink.battery}%")
                        if (SpoonLink.rssi != 0) append(" · 信号 ${SpoonLink.rssi} dBm")
                    },
                )
            }
        }

        /* ---------------------------------------------------------- 设备列表 */

        if (devices.isEmpty()) {
            item {
                ScanHint(
                    when {
                        nearbyScope && SpoonLink.scanning -> "正在扫描…请确保勺子已上电"
                        nearbyScope ->
                            "还没有扫到智味勺：确认勺子已上电、手机的蓝牙与定位开关都打开，" +
                                "然后点上面的「扫描附近的智味勺」"
                        else -> "还没有已保存的智味勺：切到「附近的智味勺」扫描并保存"
                    },
                )
            }
        } else {
            item { PrefCategory(if (nearbyScope) "附近的智味勺" else "已保存的智味勺") }
        }

        items(devices, key = { it.id }) { device ->
            DeviceRow(a, device, signalOf = scans.firstOrNull { it.address == device.id }?.rssi)
        }
    }
}

/** 设备列表里的一句提示（居中灰字）。 */
@Composable
private fun ScanHint(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/**
 * 设备列表的一行：名称 + 电量（扫描结果还会显示信号强度）+ 保存 / 连接两个动作。
 *
 * **整行可点 = 改这台勺子的名字**。点标题、副标题、图标都行；两个按钮除外 ——
 * Compose 的子节点点击不会向上冒泡，所以点「连接」不会顺手把改名弹窗也弹出来。
 */
@Composable
private fun DeviceRow(a: SettingsHost, device: Device, signalOf: Int? = null) {
    /*
     * 判断"这台是不是当前这台"，必须看**真正的链路状态**，不能只看 `State.connectedId`。
     *
     * 这里踩过一个很难查的坑：在设备页点「断开连接」之后，`State.connectedId` 仍然指着这台勺子
     * （断开只清了链路，没清"选中的设备 id"），于是：
     * - 按钮标签用的是 `SpoonLink.ready`（false）→ 显示「连接」；
     * - 而点击走的是 `connected` 分支 → 调用了「断开」。
     * 结果就是**点「连接」完全没反应** —— 标签和动作用了两套判断条件。
     *
     * 现在两者统一：只有链路真的通着才算"已连接"。BLE 设备看 [SpoonLink.ready]，
     * 非 BLE（旧行为里的服务器设备）只认 id。
     */
    val connected = device.id == State.connectedId && (!device.isBle || SpoonLink.ready)
    val context = LocalContext.current

    PrefRow(
        icon = Icons.Filled.Bluetooth,
        title = device.name,
        summary = buildString {
            // 电量未知（-1，从没连上读到过）时**不显示这一段**，而不是写"电量 -1%"
            if (device.battery in 0..100) append("剩余电量: ${device.battery}%")
            if (device.isBle) {
                if (isNotEmpty()) append(" · ")
                append("蓝牙")
            }
            // 扫描到的设备带上 RSSI：现场判断"是不是这一台、离得远不远"全靠它
            if (signalOf != null) {
                if (isNotEmpty()) append(" · ")
                append("信号 $signalOf dBm")
            }
            if (isNotEmpty()) append(" · ")
            append("点这一行改名")
        },
        onClick = { a.showRenameDevice(device.id) },
        trailing = {
            // 两个按钮竖着排：MD3 按钮比旧版胶囊按钮宽得多，并排放会把设备名挤成「张三…」，
            // 竖排后名字能完整显示。
            Column(horizontalAlignment = Alignment.End) {
                PillButton(if (device.saved) "取消保存" else "保存") {
                    val wantSaved = !device.saved
                    if (device.isBle) {
                        if (wantSaved) {
                            State.setSpoonSaved(context, device.id, true)
                        } else {
                            // 取消保存要顺手忘掉"上次连过"，否则下次开机它还会自动回连
                            State.forgetSpoon(context, device.id)
                        }
                    }
                    device.saved = wantSaved
                    a.toast(if (wantSaved) "已保存" else "已取消保存")
                    a.loadFromStore()
                }
                Spacer(Modifier.height(4.dp))
                // 标签与动作现在用**同一个** `connected`（见上面那段注释），不会再出现
                // "显示连接、点了却去执行断开"这种自相矛盾
                PillButton(if (connected) "断开连接" else "连接", filled = !connected) {
                    when {
                        // 真机：真的去连/断 GATT，连接的成败由 BleSpoon 回写状态。
                        // 连接统一走宿主的 bleReady（权限 + 蓝牙开关都由本页自己发起）
                        device.isBle && connected -> a.disconnectSpoon()
                        device.isBle -> a.bleReady { a.connectSpoon(device.id, device.name) }
                        // 服务器模拟设备：只改一下选中的 id（旧行为不变）
                        connected -> {
                            State.connectedId = null
                            a.toast("已断开连接")
                            a.loadFromStore()
                        }
                        else -> {
                            State.connectedId = device.id
                            a.toast("已连接")
                            a.loadFromStore()
                        }
                    }
                }
            }
        },
    )
}

/* ------------------------------------------------------------ p.16 菜单 */

/**
 * 菜单管理：每份菜单一行（点这一行去改它），下面按行列出它包含的食物。
 *
 * 与旧版的区别：这里的「＋」以前只弹一句"原型演示：暂不支持新增菜单"，
 * 现在真的能建菜单了 —— 点它（或点某一份菜单改它）会打开 [MenuEditorActivity]，
 * 那一页用的是与「自定义本餐菜单」**同一套选菜界面**（搜索 / 分类 / 排序 / 拍照识别加菜 / 逐行勾选），
 * 所以"加菜品到菜单"这件事在设置里也能完整做完。
 *
 * 食物的缩略行缩进到菜单名文字的位置（24dp 内边距 + 24dp 图标 + 24dp 间距 = 72dp）。
 */
@Composable
fun MenuManageScreen(a: SettingsHost) {
    val menus = State.data?.menus ?: emptyList()
    val context = LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (menus.isEmpty()) {
            item {
                EmptyHint(
                    title = "还没有菜单",
                    detail = "点下面的「＋ 新建菜单」挑几道菜存成一份菜单；" +
                        "之后在「快速开始 → 使用现有菜单快速开始」里就能一键选它。",
                )
            }
        } else {
            item { PrefCategory("本餐菜单（点一行可修改）") }
        }

        items(menus, key = { it.id }) { menu ->
            val foods = menu.foods.mapNotNull { State.food(it) }
            Column {
                PrefRow(
                    icon = Icons.Filled.MenuBook,
                    title = menu.name,
                    summary = "${foods.size} 种食物 · 点这一行修改",
                    onClick = {
                        context.startActivity(MenuEditorActivity.intentFor(context, menu.id))
                    },
                )
                foods.forEach { food -> MenuFoodLine(food) }
            }
        }

        // 「＋ 新建菜单」：真的能建（这一页以前只有一句"暂不支持"的提示）
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        context.startActivity(MenuEditorActivity.intentFor(context, null))
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建菜单")
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

/**
 * 杂项：用餐提醒、时间显示的两个开关、热量/重量单位、服务器地址（点开可改）。
 *
 * 这里的设置都是**真的生效**的：
 * - 两个时间开关决定 [Units.dateTime] 的格式串（用餐记录、记录详情、收藏时间都走它）；
 * - 两个单位决定 [Units] 的换算（用餐中读数、用餐结果、用餐记录、统计、折线图、菜品密度…）。
 *
 * 其中「统计数据汇总」与折线图的文字是读库时就算好放进 [State.data] 的，
 * 所以改完这几项顺手重读一次本地库（[SettingsHost.loadFromStore]），否则那两页会停在旧单位。
 *
 * 这一页**刻意只留"设置"**：服务器地址那一行点开就是弹窗（所以不再另给一颗「修改地址」按钮），
 * 而「从服务器导入菜品」挪到了它该在的地方 —— 菜品库（见 [MealFlowHost.importDishesFromServer]）。
 */
@Composable
fun MiscScreen(a: SettingsHost) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { PrefCategory("用餐流程") }

        /*
         * 用餐提醒：点过一次「我已知晓」就自动关掉（记在偏好里）。
         *
         * 从「设备管理」搬到这里：它描述的是**用餐流程怎么走**，与勺子设备没有关系，
         * 放在设备页里既不好找（用户想重新看到提醒时会先想到"设置 → 杂项"），
         * 也让设备页那一屏混进了两个不相干的开关。
         */
        item {
            PrefSwitchRow(
                icon = Icons.Filled.Info,
                title = "显示用餐提醒",
                summary = if (State.showMealRemind) {
                    "点「开始用餐」后先显示记录说明；点一次「我已知晓」会自动关闭这一项"
                } else {
                    "已关闭：点「开始用餐」直接进入用餐中"
                },
                checked = State.showMealRemind,
            ) { a.setShowMealRemind(it) }
        }

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
                a.loadFromStore()
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
                a.loadFromStore()
            }
        }

        item { PrefCategory("单位") }

        item {
            PrefSelectRow(
                icon = Icons.Filled.Tune,
                title = "热量单位",
                summary = "更改食物热量显示的单位",
                value = State.energyUnit,
                options = listOf("kJ", "kcal"),
            ) {
                State.energyUnit = it
                a.persistSettings()
                a.loadFromStore()
            }
        }

        item {
            PrefSelectRow(
                icon = Icons.Filled.Tune,
                title = "重量单位",
                summary = "更改食物重量显示的单位",
                value = State.weightUnit,
                options = listOf("g", "kg", "两"),
            ) {
                State.weightUnit = it
                a.persistSettings()
                a.loadFromStore()
            }
        }

        item { PrefCategory("服务器") }

        /*
         * 只留这一行"服务器地址"（点它就能改地址，行内也显示当前地址与连接状态）。
         *
         * 原来下面还并排着两颗按钮「修改地址」与「从服务器导入菜品」，都去掉了：
         * - 「修改地址」与这一行**做的是同一件事**（都打开同一个弹窗），重复；
         * - 「从服务器导入菜品」在「菜品库」页里已经有一个入口，而那里才是它该在的地方
         *   （导入的是菜品，用户找它时会去菜品库，不会来"杂项"）。
         */
        item {
            PrefRow(
                icon = Icons.Filled.Bluetooth,
                title = "服务器地址",
                summary = State.server,
                onClick = { a.showServerDialog() },
                trailing = { OnlineTail() },
            )
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

/**
 * 账户设置：**登录 / 注册** 与已登录后的账号信息。
 *
 * 与旧版（一行只读的"张三"）的差别就是这个功能本身：
 * - 未登录 → 显示登录/注册表单（用户名 + 密码），并且**允许不登录继续用**；
 * - 已登录 → 显示账号信息、改昵称、退出登录。
 *
 * 界面在 [AccountLoginScreen] / [AccountProfileScreen]，这一页只做"该显示哪一个"的分发。
 */
@Composable
fun AccountScreen(a: AccountHost) {
    val account = State.account
    if (account == null) {
        AccountLoginScreen(a)
    } else {
        AccountProfileScreen(a, account)
    }
}

/** 账户页底部那几行固定信息（关于/版本）。 */
@Composable
fun AccountAboutRows() {
    Column(Modifier.fillMaxWidth()) {
        PrefRow(
            Icons.Filled.Info,
            "关于 Gramo",
            "版本 ${BuildConfig.VERSION_NAME}（Compose + Material 3）",
        )
    }
}

/** 设置行右侧的次要文字（旧的 tailText）。 */
@Composable
private fun ValueTail(value: String) {
    Text(value, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
