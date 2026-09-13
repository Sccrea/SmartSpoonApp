package com.smartspoon.l2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspoon.l2.MainActivity
import com.smartspoon.l2.State

/*
 * 列表页的排布遵循 Gramophone 的做法：**内容整体在可折叠的大标题栏下滚动**，
 * 只有钉在底部的东西是固定不动的（标签页是底部导航，加菜页则是托盘 ——
 * 托盘现在长在 `FoodPickerActivity` 自己的 Scaffold bottomBar 里，见 MealFlowActivities.kt）。
 *
 * 这一点不是可选项：Gramophone 的大标题栏本身就占 ~112dp，再叠上「步骤条 + 搜索 +
 * 分类 + 排序」四层固定头，剩下给列表的高度会归零（实测就是这样，列表一行都看不见）。
 * 让头部跟着滚，滚下去时标题栏还会折叠成小标题，列表能拿回全部高度。
 */

/* ------------------------------------------------------- p.02 快速开始 */

@Composable
fun QuickStartScreen(a: MainActivity) {
    // 内容不满一屏，用 BounceColumn 才能拖出回弹（和用餐记录一致）
    BounceColumn(Modifier.fillMaxSize()) {
        StepBar(current = 1)
        Column(Modifier.padding(horizontal = 16.dp)) {
            BigActionCard("使用现有菜单快速开始", "挑一份已存菜单，直接连勺子开吃") { a.openMenuChooser() }
            Spacer(Modifier.height(12.dp))
            BigActionCard("自定义本餐菜单", "逐项挑选这一餐要吃的菜") { a.openFoodPicker() }
        }
    }
}

/** 设计稿里是两张通栏大卡；这里用 MD3 的 filled card 承接。 */
@Composable
private fun BigActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* -------------------------------------------------- p.02 选择本餐菜单 */

@Composable
fun MenuChooserScreen(a: MealFlowHost) {
    val menus = State.data?.menus ?: emptyList()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { StepBar(current = 1) }
        items(menus, key = { it.id }) { menu ->
            val foods = menu.foods.mapNotNull { State.food(it) }
            ListRow(
                title = menu.name,
                subtitle = "${foods.size} 种食物 · " +
                    foods.joinToString(" / ") { it.name }.ifEmpty { "暂无菜品" },
                onClick = { a.pickMenu(menu) },
                leading = { CoverBox { Icon(Icons.Filled.MenuBook, contentDescription = null) } },
            )
        }
        item {
            BodyText("点击菜单即可用这份菜单直接开始用餐；也可以返回上一页选择「自定义本餐菜单」逐项挑选。")
        }
    }
}

/* ------------------------------------- p.03 自定义本餐菜单（食物列表） */

@Composable
fun FoodPickerScreen(a: MealFlowHost) {
    val foods = State.filteredFoods()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            // 用餐中出来加菜时，这一步其实是「用餐中」，进度条不能还停在第 1 步
            item { StepBar(current = if (State.addingFood) 2 else 1) }
            item { FoodSearchField() }
            item { CategoryChips() }
            item { SortChips() }

            if (foods.isEmpty()) {
                item {
                    Text(
                        "没有匹配的食物",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            } else {
                items(foods, key = { it.id }) { food ->
                    FoodRow(
                        food = food,
                        showToggle = true,
                        onToggle = { a.toggleFood(food.id) },
                        onMore = { action -> a.handleDishAction(food, action) },
                    )
                }
            }
        }

        // 固定尾：拍照识别 / ＋新增菜品（托盘在更下面，由 FoodPickerActivity 的 Scaffold bottomBar 承担）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { a.openRecognizeDialog() }) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("拍照识别菜品")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { a.showDishEditor(null) }) { Text("＋ 新增菜品") }
        }
    }
}

/** 搜索框（对应旧版的 editText + State.query）。 */
@Composable
private fun FoodSearchField() {
    OutlinedTextField(
        value = State.query,
        onValueChange = { State.query = it },
        singleLine = true,
        placeholder = { Text("搜索...") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 分类胶囊。 */
@Composable
private fun CategoryChips() {
    val categories = State.data?.categories ?: listOf("全部")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { name ->
            FilterChip(
                selected = State.category == name,
                onClick = { State.category = name },
                label = { Text(name) },
            )
        }
    }
}

/** 排序条：两个控件与旧版语义一致。 */
@Composable
private fun SortChips() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = false,
            onClick = { State.sortDesc = !State.sortDesc },
            label = { Text("≡ 按 食用次数 排序") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = State.sortDesc,
            onClick = { State.sortDesc = !State.sortDesc },
            label = { Text(if (State.sortDesc) "倒序" else "正序") },
        )
    }
}

/* --------------------------------------------------- p.10 收藏食物 */

@Composable
fun FavoritesScreen(a: MainActivity) {
    val favorites = State.data?.foods?.filter {
        State.data?.favoriteTimes?.containsKey(it.id) == true
    } ?: emptyList()

    val shown = favorites
        .filter { State.category == "全部" || it.category == State.category }
        .filter { State.query.isEmpty() || it.name.contains(State.query, ignoreCase = true) }

    // 用 BounceColumn 而不是 LazyColumn：这一页通常不满一屏，需要拖动回弹
    BounceColumn(Modifier.fillMaxSize()) {
        FoodSearchField()
        CategoryChips()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            FilterChip(selected = false, onClick = {}, label = { Text("≡ 按 收藏时间 排序") })
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = State.sortDesc,
                onClick = { State.sortDesc = !State.sortDesc },
                label = { Text(if (State.sortDesc) "倒序" else "正序") },
            )
        }

        // 收藏夹：按分类汇总，点击即筛选
        FolderRow("全部收藏", favorites.size) { State.category = "全部" }
        listOf("肉类", "蔬菜", "水果", "其他").forEach { category ->
            val count = favorites.count { it.category == category }
            if (count > 0) FolderRow(category, count) { State.category = category }
        }

        shown.forEach { food ->
            ListRow(
                title = food.name,
                subtitle = "收藏时间: " + (State.data?.favoriteTimes?.get(food.id) ?: ""),
                leading = { CoverBox { Text(State.emojiFor(food), fontSize = 26.sp) } },
                trailing = {
                    OutlinedButton(onClick = { a.handleDishAction(food, DishAction.Edit) }) {
                        Text("编辑")
                    }
                },
            )
        }

        if (favorites.isEmpty()) {
            Text(
                "还没有收藏的菜品，在菜品列表里点「⋮ → 收藏」加入",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}

/** 收藏夹行（文件夹）。 */
@Composable
private fun FolderRow(name: String, count: Int, onClick: () -> Unit) {
    ListRow(
        title = name,
        subtitle = "$count 道",
        onClick = onClick,
        leading = { CoverBox { Text("📁", fontSize = 22.sp) } },
    )
}
