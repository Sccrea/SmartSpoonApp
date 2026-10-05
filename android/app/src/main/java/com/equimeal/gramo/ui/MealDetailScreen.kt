package com.equimeal.gramo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.equimeal.gramo.MealEditor
import com.equimeal.gramo.MealSession
import com.equimeal.gramo.State
import com.equimeal.gramo.Units

/*
 * 用餐记录详情页（点「用餐记录」里的某一条进来）。
 *
 * ## 为什么做成独立页面，而不是继续用弹窗
 *
 * 需求是"打开**类似用餐结果的页面**，用户可以查看和修改数据"。弹窗里塞不下这些：
 * 名称、时长、口数、总重量、总热量、平均两项、每一口明细、删除记录 ——
 * 而且结果页那一套（`SectionTitle` + `ResultValueRow` + 「单击数据以修正」的约定）
 * 本来就是一整页的排版。所以这一页**直接复用结果页那批行组件**，
 * 用户看到的就是熟悉的那个界面，只是数据来自历史记录。
 *
 * 修改是**即改即存**（每次编辑立刻写回本地库并同步云端），不需要"完成"按钮 ——
 * 这也和结果页不同：结果页那边还有"这一餐刚结束"的会话状态要收尾，这里纯改数据。
 */

/** 详情页宿主需要的能力（改名、改数值、删记录）。 */
interface MealDetailHost {
    fun toast(message: String)

    /** 保存「本餐菜单」的名字。 */
    fun saveMealName(name: String)

    /** 保存某一项数值（duration / bites / weight / energy）。 */
    fun saveMealField(field: String, value: String)

    /** 删除这条记录并关闭页面。 */
    fun deleteThisMeal()
}
@Composable
fun MealDetailScreen(host: MealDetailHost, editor: MealEditor) {
    var renaming by remember(editor.id) { mutableStateOf(false) }
    var deleting by remember(editor.id) { mutableStateOf(false) }
    var editingField by remember(editor.id) { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            /*
             * 起止时间都显示，并且**用时间戳现格式化**（`Units.dateTime`）——
             * 这样「设置 → 杂项」里的「时间显示年 / 秒」两个开关一改，这一页立刻跟着变，
             * 而不是显示库里读出来时就算好的旧文本。
             */
            Text(
                "用餐时间：${Units.dateTime(editor.startedAt)} 至 ${Units.dateTime(editor.endedAt)}" +
                    "　·　单击数据可修改",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 「本餐菜单」：与结果页同一行样式，点一下改名
        item {
            ResultValueRow("本餐菜单:", editor.name) { renaming = true }
        }

        item { SectionTitle("本餐数据:") }
        item {
            ResultValueRow("用餐时长:", "${editor.minutes} 分钟") { editingField = "duration" }
        }
        item {
            ResultValueRow("已记录口数:", editor.bites.toString()) { editingField = "bites" }
        }
        item {
            ResultValueRow("已记录总重量:", Units.weight(editor.weightGrams)) {
                editingField = "weight"
            }
        }
        item {
            ResultValueRow("已记录总热量:", Units.energy(editor.energyKj)) {
                editingField = "energy"
            }
        }

        // 平均两项是算出来的，与结果页共用同一个清单
        items(mealDerivedRows(editor.avgWeightGrams, editor.avgEnergyKj)) { row ->
            when (row) {
                is MealRow.Section -> SectionTitle(row.title)
                is MealRow.Value -> ResultValueRow(row.key, row.value)
            }
        }

        item { SectionTitle("每一口明细:") }
        if (editor.biteList.isEmpty()) {
            item {
                BodyText("这条记录没有留存每一口明细（可能是较早的记录，或当时没有记下任何一口）。")
            }
        } else {
            items(editor.biteList) { bite ->
                BodyText(
                    "· ${bite.dish.ifBlank { "未指定" }}　${Units.weight(bite.weight)}　" +
                        Units.energy(bite.energy) + "　${Units.dateTime(bite.at)}",
                )
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "改动会立刻保存到本地；登录状态下同时同步到云端。",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 删除做成"点一下变成确认"的两步：这个动作不可撤销
                if (deleting) {
                    OutlinedButton(onClick = { deleting = false }) { Text("取消") }
                    Button(onClick = { host.deleteThisMeal() }) {
                        Text("确认删除", color = MaterialTheme.colorScheme.onError)
                    }
                } else {
                    OutlinedButton(onClick = { deleting = true }) {
                        Text("删除记录", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    /* ---------------------------------------------------------------- 弹窗 */

    if (renaming) {
        EditMealNameCard(
            editor = editor,
            onSave = { text ->
                renaming = false
                host.saveMealName(text)
            },
            onCancel = { renaming = false },
        )
    }

    editingField?.let { field ->
        // 与结果页的「修正数据」弹窗同一套排版（标题 + 当前值 + 输入框 + 取消/保存）
        EditMealFieldCard(
            editor = editor,
            field = field,
            onSave = { text ->
                editingField = null
                host.saveMealField(field, text)
            },
            onCancel = { editingField = null },
        )
    }
}

/** 「本餐菜单名称」弹窗。 */
@Composable
private fun EditMealNameCard(
    editor: MealEditor,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(editor.id, editor.name) { mutableStateOf(editor.name) }

    ModalCard(onDismiss = onCancel) {
        DialogTitle("本餐菜单名称")
        DialogLine("这个名字会作为这一餐在「用餐记录」里的名称。", 13.sp, muted = true)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        DialogActions {
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = { onSave(text) }) { Text("保存") }
        }
    }
}

/** 「修正某一项数值」弹窗（时长 / 口数 / 重量 / 热量）。 */
@Composable
private fun EditMealFieldCard(
    editor: MealEditor,
    field: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val number = when (field) {
        "duration" -> editor.minutes.toString()
        "bites" -> editor.bites.toString()
        "weight" -> Units.weightNumber(editor.weightGrams)
        else -> Units.energyNumber(editor.energyKj)
    }
    val unitLabel = when (field) {
        "duration" -> "分钟"
        "bites" -> "口"
        "weight" -> Units.weightUnitLabel()
        else -> Units.energyUnitLabel()
    }
    var text by remember(field, number) { mutableStateOf(number) }

    ModalCard(onDismiss = onCancel) {
        DialogTitle("修正数据")
        DialogLine("当前值: $number $unitLabel", 13.sp, muted = true)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        DialogActions {
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = { onSave(text) }) { Text("保存") }
        }
    }
}
