package com.equimeal.gramo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.equimeal.gramo.MenuDef
import com.equimeal.gramo.State

/*
 * 菜单编辑页（新建 / 修改一份菜单）。
 *
 * ## 为什么单独做一页，而不是在「设置 → 菜单」里弹个框
 *
 * 需求是"菜单页要能实际创建和添加菜品，界面类似自定义本餐菜单"。那就意味着它需要
 * **完整的选菜界面**：搜索、分类、排序、拍照识别加菜、逐行勾选 —— 这些东西塞进弹窗里没法用。
 * 所以这一页直接复用 [FoodPickerItems]（与「自定义本餐菜单」同一份实现），
 * 只在顶部多一个菜单名输入框、底部把「选好了」换成「保存菜单」。
 *
 * 两处共用同一个列表还有一个好处：以后改选菜体验（比如加个"最近常吃"分组），
 * 菜单编辑页自动跟着变，不会出现"两个地方长得不一样"。
 */

/** 菜单编辑页的宿主能力（比 [MealFlowHost] 多"保存/删除"两个动作）。 */
interface MenuEditorHost : MealFlowHost {
    /** 保存：[menuId] 为 null 表示新建。 */
    fun saveMenu(menuId: String?, name: String, dishIds: List<String>)

    /** 删除当前这份菜单（只在编辑已有菜单时可用）。 */
    fun deleteMenu(menuId: String)

    /** 是否 `this` 这一页正在编辑一份已有菜单（决定标题与按钮文案）。 */
    val editing: MenuDef?
}

/**
 * 菜单编辑页内容。
 *
 * [host] 提供"保存/删除"，[a] 提供选菜所需的全部动作（两者通常是同一个 Activity）。
 */
@Composable
fun MenuEditorScreen(host: MenuEditorHost) {
    val editing = host.editing
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var confirmDelete by remember(editing?.id) { mutableStateOf(false) }

    FoodPickerList(
        a = host,
        header = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("菜单名称") },
                        placeholder = { Text("例如：减脂餐、周末晚餐") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (editing == null) {
                            "在下面勾选这份菜单包含的菜品，勾完点「保存菜单」。"
                        } else {
                            "改名字或增删菜品后点「保存菜单」；这份菜单的改动会立刻生效。"
                        },
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        bottom = {
            MenuEditorBottomBar(
                host = host,
                name = { name },
                editing = editing,
                confirmDelete = confirmDelete,
                onDeleteRequest = { confirmDelete = true },
                onDeleteCancel = { confirmDelete = false },
                onDeleteConfirm = { editing?.let { host.deleteMenu(it.id) } },
            )
        },
    )
}

/** 底部：已选几道菜 + 保存（编辑时还有删除）。 */
@Composable
private fun MenuEditorBottomBar(
    host: MenuEditorHost,
    name: () -> String,
    editing: MenuDef?,
    confirmDelete: Boolean,
    onDeleteRequest: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDeleteConfirm: () -> Unit,
) {
    val chosen = State.selected.mapNotNull { State.food(it) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                if (chosen.isEmpty()) {
                    "还没有选择食物，点菜品右侧的「＋」加入这份菜单"
                } else {
                    "已选 ${chosen.size} 种食物：" + chosen.joinToString("、") { it.name }
                },
                fontSize = 13.sp,
                fontWeight = if (chosen.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { host.saveMenu(editing?.id, name(), State.selected.toList()) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (editing == null) "保存菜单" else "保存修改") }

                if (editing != null) {
                    // 删除做成"点一下变成确认"的两步：这个动作不可撤销，不能一击致命
                    if (confirmDelete) {
                        TextButton(onClick = onDeleteCancel) { Text("取消") }
                        TextButton(onClick = { onDeleteCancel(); onDeleteConfirm() }) {
                            Text("确认删除", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        OutlinedButton(onClick = onDeleteRequest) {
                            Text("删除菜单", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
