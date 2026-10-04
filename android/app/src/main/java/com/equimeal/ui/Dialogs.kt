package com.equimeal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.equimeal.AppDialog
import com.equimeal.BleSpoon
import com.equimeal.DialogActions
import com.equimeal.Bite
import com.equimeal.MealRecord
import com.equimeal.MealSession
import com.equimeal.SpoonLink
import com.equimeal.State
import com.equimeal.Units

/*
 * 弹窗（p.04~p.06 连接智味勺 / 用餐提醒 / 服务器地址，识别结果，修正数据，菜品增删改…）。
 *
 * 旧版这些弹窗是 MainActivity 里用 `android.app.Dialog` + 手写 View 拼出来的；迁移后
 * 宿主只负责往 [State.dialog] 写「要显示哪一个」，真正的界面在这里渲染，
 * 而弹窗按钮要做的业务逻辑由宿主实现 [DialogActions] 承担：主界面是 `MainActivity`，
 * 用餐流程那四个页面是 `BaseMealActivity` —— 两者都转发到共享的 `AppCore`。
 *
 * 约定与其它页面一致：
 * - 颜色一律取 `MaterialTheme.colorScheme`，没有任何硬编码色值；
 * - 多字段/多动作的弹窗用 28dp 圆角的卡片（旧版 window 背景的圆角就是这个数），
 *   宽度也照旧：屏宽的 88%，最多 340dp；内容超高时可滚动；
 * - 所有数值都在渲染时从 [State] 现读，所以弹窗开着的时候数据变化会立刻反映出来
 *   （旧版是打开时拼一次 View，之后就不动了）。
 */
@Composable
fun AppDialogs(d: DialogActions) {
    when (val dialog = State.dialog) {
        null -> Unit
        is AppDialog.Connect -> ConnectDialog(d, dialog.picking)
        AppDialog.Remind -> RemindDialog(d)
        is AppDialog.RenameDevice -> RenameDeviceDialog(d, dialog.deviceId)
        AppDialog.Server -> ServerDialog(d)
        is AppDialog.EditResult -> EditResultDialog(d, dialog.field)
        AppDialog.BiteDetail -> BiteDetailDialog(d)
        is AppDialog.MealDetail -> MealDetailDialog(d, dialog.record)
        AppDialog.MealName -> MealNameDialog(d)
        is AppDialog.DishEditor -> DishEditorDialog(d, dialog.foodId)
        is AppDialog.DeleteDish -> DeleteDishDialog(d, dialog.foodId)
        AppDialog.Recognize -> RecognizeDialog(d)
        AppDialog.Recognizing -> RecognizingDialog(d)
        is AppDialog.RecognizeResult -> RecognizeResultDialog(d, dialog)
    }
}

/* ------------------------------------------------------- p.04 / p.05 连接智味勺 */

/**
 * 连接智味勺。
 *
 * `picking = true` 时列出**扫到的真机**（[SpoonLink.detected]），点一行就发起 BLE 连接；
 * 否则只显示当前连接。
 *
 * 与旧版（只显示服务器那份示例设备）的关键差别：
 * - 列表来自真实扫描，带 RSSI，并按信号强度排序；
 * - 每行显示的是**连接状态**（连接中 / 已连接 / 点一下连接），不是一个单选圆点 ——
 *   真机上"选中"和"连上了"是两件事，界面必须说清楚现在到底连上没有；
 * - 权限没给时把「重新扫描」换成「授予蓝牙权限」，而不是让用户点了没反应。
 *
 * 服务器那份模拟设备仍然可用（离线演示），但它的入口在「设备管理」里，不混进这份
 * 扫描结果 —— 真机列表里塞几个永远扫不到的假设备，只会让人分不清哪个是真的。
 */
@Composable
private fun ConnectDialog(d: DialogActions, picking: Boolean) {
    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle("连接智味勺")

        if (picking) {
            // 扫描状态机：正在扫 / 失败原因 / 扫到几台，都直接摆出来
            val scanning = SpoonLink.scanning
            val status = SpoonLink.status
            DialogLine(
                when {
                    scanning -> "正在扫描附近的智味勺…"
                    status == SpoonLink.Status.CONNECTING -> SpoonLink.statusText
                    status == SpoonLink.Status.FAILED -> SpoonLink.statusText
                    else -> "附近的智味勺:"
                },
                13.sp,
                align = TextAlign.Start,
            )

            val spoons = BleSpoon.detectedSorted()
            if (spoons.isEmpty()) {
                DialogLine(
                    when {
                        scanning -> "还没发现设备，请确认勺子已上电"
                        SpoonLink.hasBluetooth() && !SpoonLink.isBluetoothOn() -> "蓝牙没有打开"
                        else -> "没有发现智味勺，点「重新扫描」再试"
                    },
                    14.sp,
                    muted = true,
                    topPadding = 10.dp,
                )
            }

            spoons.forEach { spoon ->
                val connected = SpoonLink.ready && SpoonLink.connectedAddress == spoon.address
                val connecting = status == SpoonLink.Status.CONNECTING &&
                    SpoonLink.connectedAddress == spoon.address
                val selected = spoon.address == State.connectedId
                /*
                 * 电量：**没连上就不显示**，连上了才显示。
                 *
                 * BLE 的广播包里没有电量，只有连上之后读得到（见下面的取值）。所以：
                 * - 当前连着的那台 → 用 [SpoonLink.battery]（实时值），显示「电量 87%」；
                 * - 以前连过、有缓存值 → 也显示（比"未知"有用）；
                 * - 从没连过 → **整段不显示**，副标题只留信号强度与"点击连接"。
                 *
                 * 这里刻意不写"电量未知"：那三个字占着位置却什么也没告诉用户，
                 * 而"没连上就不知道电量"本来就是理所当然的事，不需要解释。
                 */
                val batteryLevel = if (connected && SpoonLink.battery in 0..100) {
                    SpoonLink.battery
                } else {
                    spoon.battery
                }
                val batteryText = if (batteryLevel in 0..100) "电量 $batteryLevel%" else null
                Surface(
                    color = if (selected || connected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected || connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                ) {
                    ListRow(
                        title = spoon.displayName,
                        // 用 listOfNotNull(...).joinToString 拼：电量缺失时那一段自然消失，不留多余分隔符
                        subtitle = when {
                            connected -> listOfNotNull(
                                "已连接",
                                batteryText,
                                "信号 ${spoon.rssi} dBm",
                            ).joinToString(" · ")

                            connecting -> "正在连接…"
                            else -> listOfNotNull(
                                batteryText,
                                "信号 ${spoon.rssi} dBm",
                                "点击连接",
                            ).joinToString(" · ")
                        },
                        minHeight = 56.dp,
                        onClick = { d.connectSpoon(spoon.address, spoon.displayName) },
                        leading = {
                            RadioButton(selected = connected || connecting, onClick = null)
                        },
                    )
                }
            }

            DialogLine("已连接:", 14.sp, topPadding = 16.dp)
            DialogLine(
                when {
                    SpoonLink.ready -> "${SpoonLink.connectedName.ifBlank { "智味勺" }}（蓝牙）"
                    else -> State.connectedDevice()?.name ?: "未连接"
                },
                19.sp,
                bold = true,
            )
            if (SpoonLink.ready) {
                DialogLine(SpoonLink.statusText, 12.5.sp, muted = true)
            } else {
                DialogLine("还没有连接智味勺：请在上面选一台点一下连接", 12.5.sp, muted = true)
            }
            DialogLine(
                "当前食物重量: ${Units.weight(State.meal.spoonWeight.toDouble())}",
                14.sp,
                topPadding = 12.dp,
            )
        } else {
            DialogLine("当前已连接:", 14.sp, topPadding = 8.dp)
            DialogLine(
                when {
                    SpoonLink.ready -> "${SpoonLink.connectedName.ifBlank { "智味勺" }}（蓝牙）"
                    else -> State.connectedDevice()?.name ?: "未连接"
                },
                19.sp,
                bold = true,
            )
            DialogLine(
                "剩余电量: ${
                    when {
                        SpoonLink.ready && SpoonLink.battery in 0..100 -> SpoonLink.battery
                        else -> State.connectedDevice()?.battery ?: 0
                    }
                }%",
                12.5.sp,
                muted = true,
            )
            DialogLine("当前食物重量: ${Units.weight(State.meal.spoonWeight.toDouble())}", 14.sp, topPadding = 18.dp)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            OutlinedButton(onClick = { d.zeroSpoonWeight() }) { Text("点击归零重量") }
        }

        if (!picking) {
            DialogLine("点击 重新选择 以连接\n附近的其他智味勺", 14.sp, topPadding = 14.dp)
        }

        DialogActions {
            TextButton(onClick = { d.closeOverlay() }) { Text("取消") }
            if (picking) {
                // 权限还没给时，先给用户一个明确的按钮：否则点「重新扫描」只会永远扫不出东西
                if (!State.hasBlePermissions(LocalContext.current)) {
                    TextButton(onClick = { d.grantBlePermission() }) { Text("授予蓝牙权限") }
                } else {
                    TextButton(onClick = { d.scanSpoons() }) { Text("重新扫描") }
                }
                if (SpoonLink.ready) {
                    TextButton(onClick = { d.disconnectSpoon() }) { Text("断开") }
                }
            } else {
                TextButton(onClick = { d.showConnectDialog(true) }) { Text("重新选择") }
            }
            /*
             * 「开始用餐」**必须连着真勺子**才可用。
             *
             * 为什么不用 enabled=false 那个不明显、又说不清原因的灰按钮：这里用
             * "点了会告诉你为什么"的方式 —— 没连上时按钮文案直接写成「先连接智味勺」，
             * 点一下就把用户带到扫描列表。这样既不误触，也不会卡在一个点不动的按钮上。
             */
            if (SpoonLink.ready) {
                TextButton(onClick = { d.startMeal() }) { Text("开始用餐") }
            } else {
                TextButton(onClick = { d.showConnectDialog(true) }) { Text("先连接智味勺") }
            }
        }
    }
}

/* ------------------------------------------------------------- p.06 用餐提醒 */

@Composable
private fun RemindDialog(d: DialogActions) {
    AlertDialog(
        onDismissRequest = { d.closeOverlay() },
        title = { DialogTitle("用餐提醒") },
        text = {
            Column {
                Text(
                    "在用餐过程中，\"EquiMeal\"会持续记录您使用智味勺用餐的情况（如食物种类，热量等）。" +
                        "当您关闭手机屏幕或使用其他App时，\"EquiMeal\"将保持在后台运行。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "单击 我已知晓 以开始记录",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { d.confirmRemindAndStart() }) { Text("我已知晓") }
        },
        dismissButton = {
            TextButton(onClick = { d.closeOverlay() }) { Text("退出记录") }
        },
    )
}

/* --------------------------------------------------- 设备改名（设备管理页） */

/**
 * 改这台勺子的名字。
 *
 * 名称只是**给人看的标签**：协议里的识别靠 MAC 地址，所以改名不会影响连接、
 * 也不影响哪个数据帧属于哪台勺子。真机的名字会存在偏好里（重启后还在），
 * 下一次自动回连也用这个名字显示。
 */
@Composable
private fun RenameDeviceDialog(d: DialogActions, deviceId: String) {
    val device = State.data?.devices?.firstOrNull { it.id == deviceId }
    var text by remember(deviceId) { mutableStateOf(device?.name ?: "") }

    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle("修改勺子名称")
        DialogLine(
            if (device?.isBle == true) "蓝牙地址：$deviceId" else "这是服务器提供的示例设备",
            12.5.sp,
            muted = true,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("例如：我的智味勺") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        DialogLine("名字只影响显示，不影响连接与记录", 12.5.sp, muted = true, topPadding = 8.dp)
        DialogActions {
            TextButton(onClick = { d.closeOverlay() }) { Text("取消") }
            TextButton(onClick = { d.renameDevice(deviceId, text) }) { Text("保存") }
        }
    }
}

/* --------------------------------------------------------- 设置 → 服务器地址 */

@Composable
private fun ServerDialog(d: DialogActions) {
    // 旧版是打开弹窗时用 State.server 预填输入框，之后由用户改
    var text by remember { mutableStateOf(State.server) }

    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle("服务器地址")
        DialogLine("应用启动时从这里读取菜品信息", 12.5.sp, muted = true)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("http://192.168.x.x:5000") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        DialogActions {
            TextButton(onClick = { d.closeOverlay() }) { Text("取消") }
            TextButton(onClick = { d.goOfflineDemo() }) { Text("离线演示") }
            TextButton(onClick = { d.saveServerAddress(text) }) { Text("保存并导入") }
        }
    }
}

/* ------------------------------------------------------------- 修正数据 */

@Composable
private fun EditResultDialog(d: DialogActions, field: String) {
    /*
     * 输入框里是**当前单位下的数值**：热量按 kJ / kcal，重量按 g / kg / 两。
     * 保存在 AppCore.saveResultField 里换算回基准单位（kJ / g），
     * 所以这里改了单位之后再打开这个弹窗，预填的数字也跟着换。
     */
    val number = when (field) {
        "duration" -> State.result.minutes.toString()
        "bites" -> State.result.bites.toString()
        "weight" -> Units.weightNumber(State.result.weightGrams)
        else -> Units.energyNumber(State.result.energyKj)
    }
    val unitLabel = when (field) {
        "duration" -> "分钟"
        "bites" -> "口"
        "weight" -> Units.weightUnitLabel()
        else -> Units.energyUnitLabel()
    }
    var text by remember(field, number) { mutableStateOf(number) }

    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle("修正数据")
        DialogLine("当前值: $number $unitLabel", 13.sp, muted = true)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        DialogActions {
            TextButton(onClick = { d.closeOverlay() }) { Text("取消") }
            TextButton(onClick = { d.saveResultField(field, text) }) { Text("保存") }
        }
    }
}

/* --------------------------------------------------------- 每口详细数据 */

@Composable
private fun BiteDetailDialog(d: DialogActions) {
    AlertDialog(
        onDismissRequest = { d.closeOverlay() },
        title = { DialogTitle("每口详细数据") },
        text = { BiteLines(d.currentBiteList(), emptyText = "本次用餐还没有记录到任何一口") },
        confirmButton = {
            TextButton(onClick = { d.closeOverlay() }) { Text("关闭") }
        },
    )
}

/* --------------------------------------------------------- 用餐记录详情 */

@Composable
private fun MealDetailDialog(d: DialogActions, record: MealRecord) {
    // 每一口只在记录变化时再查一次库（与旧版「打开弹窗时查一次」等价）
    val bites = remember(record.id) { d.mealBites(record.id) }

    AlertDialog(
        onDismissRequest = { d.closeOverlay() },
        title = { DialogTitle(record.menu) },
        text = {
            Column {
                Text(
                    "${if (record.startedAt > 0L) Units.dateTime(record.startedAt) else record.start} 起",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                BiteLines(bites, emptyText = "没有明细")
            }
        },
        confirmButton = {
            TextButton(onClick = { d.closeOverlay() }) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = { d.deleteMealConfirmed(record.id) }) { Text("删除记录") }
        },
    )
}

/* ------------------------------------------------- 用餐结束后给这一餐命名 */

/**
 * 「本餐菜单」改名（结果页点那一行）。
 *
 * 两种用餐入口都能改：用「使用现有菜单快速开始」时预填的是那份菜单的名字，
 * 用「自定义本餐菜单」时预填「自定义菜单」。保存后同时写回用餐记录。
 */
@Composable
private fun MealNameDialog(d: DialogActions) {
    // 预填结果页正在显示的名字（而不是硬编码默认值），这样"打开就想微调一下"最省事
    var name by remember { mutableStateOf(State.result.mealName) }

    AlertDialog(
        onDismissRequest = { d.closeOverlay() },
        title = { DialogTitle("本餐菜单名称") },
        text = {
            Column {
                Text(
                    "这个名字会作为这一餐在「用餐记录」里的名称。清空则保持原来的名字。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("这一餐的名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { d.renameMeal(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = { d.closeOverlay() }) { Text("取消") }
        },
    )
}

/* --------------------------------------------------------- 新增 / 编辑菜品 */

@Composable
private fun DishEditorDialog(d: DialogActions, foodId: String?) {
    // 编辑时按 id 现读，避免拿着可能已经被替换掉的 Food 对象
    val food = State.food(foodId)
    var name by remember(foodId) { mutableStateOf(food?.name ?: "") }
    /*
     * 输入框里填的是**当前单位下的密度** —— 单位由「设置 → 杂项」的热量单位与重量单位共同决定
     * （`kJ/g`、`kcal/kg`、`kJ/两`…）。库里的基准一直是「每克多少 kJ」，
     * 所以预填时换算出来、保存时用 Units.toDensity 换算回去。
     */
    var density by remember(foodId) { mutableStateOf(Units.densityNumber(food?.density ?: 2.0)) }
    var category by remember(foodId) { mutableStateOf(food?.category ?: "肉类") }

    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle(if (foodId == null) "新增菜品" else "编辑菜品")

        FieldLabel("名称")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text("菜品名称") },
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("能量密度 (${Units.densityUnitLabel()})", topPadding = 10.dp)
        OutlinedTextField(
            value = density,
            onValueChange = { density = it },
            singleLine = true,
            placeholder = { Text("能量密度 ${Units.densityUnitLabel()}") },
            // 旧版这一项是数字键盘（TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_DECIMAL）
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("分类", topPadding = 10.dp)
        CategoryPicker(category) { category = it }

        DialogActions {
            TextButton(onClick = { d.closeOverlay() }) { Text("取消") }
            TextButton(
                onClick = {
                    /*
                     * 存进库的始终是基准单位 kJ/g。
                     *
                     * 用户**没动过这一栏**（文本与预填的一模一样）时，直接把原值传回去，
                     * 不走「显示 → 解析 → 换算」那条路：预填的字符串按当前单位四舍五入过，
                     * 换算回来会有极小漂移（8.2 kJ/g 显示成 98kcal/两，再换回去是 8.1998），
                     * 每次打开又保存都磨掉一点。改过才按用户输入换算。
                     */
                    val parsed = density.toDoubleOrNull() ?: Units.densityValue(2.0)
                    val canonical =
                        if (food != null && Units.densityNumber(food.density) == density) {
                            food.density
                        } else {
                            Units.toDensity(parsed)
                        }
                    d.saveDishFromEditor(foodId, name, category, canonical)
                },
            ) { Text("保存") }
        }
    }
}

/** 旧版 `selectBox` 的 Compose 对应物：显示当前分类的胶囊 + MD3 下拉。 */
@Composable
private fun CategoryPicker(value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text("$value  ▾") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("肉类", "蔬菜", "水果", "其他").forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/* ------------------------------------------------------------- 删除菜品 */

@Composable
private fun DeleteDishDialog(d: DialogActions, foodId: String) {
    val food = State.food(foodId)

    AlertDialog(
        onDismissRequest = { d.closeOverlay() },
        title = { DialogTitle("删除菜品") },
        text = {
            Text(
                "确定删除「${food?.name ?: ""}」吗？",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = { d.deleteDishConfirmed(foodId) }) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = { d.closeOverlay() }) { Text("取消") }
        },
    )
}

/* --------------------------------------------------------------- 拍照识别 */

@Composable
private fun RecognizeDialog(d: DialogActions) {
    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle("拍照识别菜品")
        DialogLine("拍摄或选择一张食物照片，由百度菜品识别返回结果", 12.5.sp, muted = true)
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { d.takePhoto() },
                modifier = Modifier.weight(1f),
            ) { Text("📷 拍照") }
            OutlinedButton(
                onClick = { d.pickImage() },
                modifier = Modifier.weight(1f),
            ) { Text("选择图片") }
        }
        DialogActions {
            TextButton(onClick = { d.closeOverlay() }) { Text("关闭") }
        }
    }
}

/** 识别中：旧版就是一行「正在识别…」，没有任何按钮。 */
@Composable
private fun RecognizingDialog(d: DialogActions) {
    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle("拍照识别菜品")
        DialogLine("正在识别…", 14.sp, muted = true)
    }
}

/** 识别结果（message == null 表示成功）。 */
@Composable
private fun RecognizeResultDialog(d: DialogActions, result: AppDialog.RecognizeResult) {
    val message = result.message

    if (message != null) {
        AlertDialog(
            onDismissRequest = { d.closeOverlay() },
            title = { DialogTitle("识别失败") },
            text = {
                Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            },
            confirmButton = {
                TextButton(onClick = { d.closeOverlay() }) { Text("关闭") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { d.closeOverlay() },
        title = { DialogTitle("识别结果（Top ${result.count}）") },
        text = {
            if (result.items.isEmpty()) {
                Text(
                    "没有识别出菜品",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    result.items.forEachIndexed { index, item ->
                        // 本地菜品库里已经有同名菜 → 加入菜单；否则保存到服务器菜品库
                        val local = State.data?.foods?.firstOrNull { it.name == item.name }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}. ${item.name}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${item.percent}%",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (local != null) {
                                TextButton(onClick = { d.addRecognizedToMenu(local.id) }) {
                                    Text("加入菜单")
                                }
                            } else {
                                TextButton(
                                    onClick = { d.saveDishToServer(item.name, item.calorie) },
                                ) { Text("保存到菜品库") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { d.closeOverlay() }) { Text("关闭") }
        },
    )
}

/* ------------------------------------------------------------------ 零件 */

/**
 * 多字段 / 多动作弹窗的外壳：旧版 `showOverlay` 里那张卡片。
 *
 * 用 `usePlatformDefaultWidth = false` 把窗口铺满，再自己收成「屏宽 88%、最多 340dp」
 * ——和旧版 `window.setLayout(min(屏宽*0.88, 340dp), WRAP_CONTENT)` 的宽度一致；
 * 内容超过一屏时纵向可滚动（旧版会直接顶出屏幕）。
 */
@Composable
internal fun ModalCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 340.dp)
                    .heightIn(max = 560.dp),
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/** 弹窗标题（旧版 modalTitle：16sp 粗体、居中）。 */
@Composable
internal fun DialogTitle(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
    )
}

/** 弹窗里的一行正文（旧版 modalLine：居中、上下各 3dp 留白）。 */
@Composable
internal fun DialogLine(
    text: String,
    fontSize: TextUnit,
    bold: Boolean = false,
    muted: Boolean = false,
    align: TextAlign = TextAlign.Center,
    topPadding: Dp = 3.dp,
) {
    Text(
        text,
        fontSize = fontSize,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
        textAlign = align,
        modifier = Modifier.fillMaxWidth().padding(top = topPadding, bottom = 3.dp),
    )
}

/** 输入框上方的小标签（旧版是一行 12.5sp 的灰字）。 */
@Composable
private fun FieldLabel(text: String, topPadding: Dp = 0.dp) {
    Text(
        text,
        fontSize = 12.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = topPadding, bottom = 4.dp),
    )
}

/** 弹窗底部的动作行（旧版 modalActions：右对齐的一排文字按钮）。 */
@Composable
internal fun DialogActions(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 每一口的明细行（「每口详细数据」与「用餐记录详情」共用）。 */
@Composable
private fun BiteLines(bites: List<Bite>, emptyText: String) {
    if (bites.isEmpty()) {
        Text(emptyText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        return
    }
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        bites.forEachIndexed { index, bite ->
            Text(
                "第 ${index + 1} 口   ${bite.dish}   ${Units.weight(bite.weight)} · ${Units.energy(bite.energy)}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}
