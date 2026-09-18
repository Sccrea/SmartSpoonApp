package com.smartspoon.l2.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartspoon.l2.AppDialog
import com.smartspoon.l2.DialogActions
import com.smartspoon.l2.Bite
import com.smartspoon.l2.MealRecord
import com.smartspoon.l2.State
import com.smartspoon.l2.Units

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
        AppDialog.Server -> ServerDialog(d)
        is AppDialog.EditResult -> EditResultDialog(d, dialog.field)
        AppDialog.BiteDetail -> BiteDetailDialog(d)
        is AppDialog.MealDetail -> MealDetailDialog(d, dialog.record)
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
 * `picking = true` 时列出附近可选的勺子（`Device.picker`），点一行就是选中它；
 * 否则只显示当前连接。选中态、电量、食物重量全部现读 [State]，
 * 所以「点击归零重量」之后那一行会立刻变成 0g，不用像旧版那样重开弹窗。
 */
@Composable
private fun ConnectDialog(d: DialogActions, picking: Boolean) {
    ModalCard(onDismiss = { d.closeOverlay() }) {
        DialogTitle("连接智味勺")

        if (picking) {
            DialogLine("附近的智味勺:", 13.sp, align = TextAlign.Start)

            (State.data?.devices ?: emptyList()).filter { it.picker }.forEach { device ->
                val selected = device.id == State.connectedId
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                ) {
                    ListRow(
                        title = device.name,
                        subtitle = "剩余电量: ${device.battery}%",
                        minHeight = 56.dp,
                        onClick = { d.selectConnectedDevice(device.id) },
                        leading = { RadioButton(selected = selected, onClick = null) },
                    )
                }
            }

            DialogLine("已连接:", 14.sp, topPadding = 16.dp)
            DialogLine(State.connectedDevice()?.name ?: "未连接", 19.sp, bold = true)
            DialogLine(
                "当前食物重量: ${Units.weight(State.meal.spoonWeight.toDouble())}",
                14.sp,
                topPadding = 12.dp,
            )
        } else {
            DialogLine("当前已连接:", 14.sp, topPadding = 8.dp)
            DialogLine(State.connectedDevice()?.name ?: "未连接", 19.sp, bold = true)
            DialogLine(
                "剩余电量: ${State.connectedDevice()?.battery ?: 0}%",
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
            if (!picking) {
                TextButton(onClick = { d.showConnectDialog(true) }) { Text("重新选择") }
            }
            TextButton(onClick = { d.startMeal() }) { Text("开始用餐") }
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
                    "在用餐过程中，\"智味勺App\"会持续记录您使用智味勺用餐的情况（如食物种类，热量等）。" +
                        "当您关闭手机屏幕或使用其他App时，\"智味勺App\"将保持在后台运行。",
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
private fun ModalCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
private fun DialogTitle(text: String) {
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
private fun DialogLine(
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
private fun DialogActions(content: @Composable RowScope.() -> Unit) {
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
