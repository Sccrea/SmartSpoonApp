package com.equimeal.gramo.ui

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.equimeal.gramo.MainActivity
import com.equimeal.gramo.State
import com.equimeal.gramo.Units
import android.content.Intent

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
    val context = LocalContext.current
    // 打开"新建菜单"页（菜单编辑页与自定义本餐菜单共用同一套选菜界面）
    val newMenu: () -> Unit = {
        context.startActivity(Intent(context, MenuEditorActivity::class.java))
    }
    val menus = State.data?.menus ?: emptyList()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { StepBar(current = 1) }

        if (menus.isEmpty()) {
            item {
                EmptyHint(
                    title = "还没有保存过菜单",
                    detail = "点下面的「＋ 新建菜单」挑几道菜存成一份菜单，" +
                        "以后就能在这里一键选它开始用餐。也可以返回上一页用「自定义本餐菜单」逐项挑选。",
                )
            }
        }

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

        /*
         * 「＋ 新建菜单」。
         *
         * 原来这一页只能"挑已有的菜单"，一旦一份菜单都没有就是个死胡同 ——
         * 用户只能退回去走「自定义本餐菜单」。这里补上一个入口，
         * 直接进菜单编辑页（与自定义本餐菜单同一套选菜界面），存完回到本页即可选它。
         */
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = newMenu) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建菜单")
                }
                Spacer(Modifier.weight(1f))
                if (menus.isNotEmpty()) {
                    Text(
                        "点菜单即可开始用餐",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ------------------------------------- p.03 自定义本餐菜单（食物列表） */

@Composable
fun FoodPickerScreen(a: MealFlowHost) {
    FoodPickerList(a)
}

/**
 * 菜品列表主体（搜索 / 分类 / 排序 + 菜品行）。
 *
 * 抽出来是因为**「设置 → 菜单」编辑一份菜单时用的是同一个界面**：
 * 需求是"菜单页要像自定义本餐菜单那样能选菜品"。与其抄一份，不如让两处共用 ——
 * 唯一不同的是底部动作（这里是「选好了」进用餐流程，菜单编辑页是「保存」这份菜单）。
 */
@Composable
fun FoodPickerList(
    a: MealFlowHost,
    header: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        header()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            FoodPickerItems(a)
        }
        bottom()
    }
}

/** 菜品列表的条目（进度条 / 搜索 / 分类 / 排序 / 菜品行 / 空状态）。 */
fun LazyListScope.FoodPickerItems(a: MealFlowHost) {
    val foods = State.filteredFoods()

    // 菜单编辑页也复用这份列表，但它不属于用餐流程：那时不画步骤条
    if (!State.editingMenu) {
        // 用餐中出来加菜时，这一步其实是「用餐中」，进度条不能还停在第 1 步
        item { StepBar(current = if (State.addingFood) 2 else 1) }
    }
    item { FoodSearchField() }
    item { CategoryChips() }
    item {
        // 菜品列表的排序：字母 / 收藏时间 / 食用次数 / 能量密度 × 正序倒序
        SortBar(
            current = State.foodSort,
            options = State.FoodSort.entries.toList(),
            label = { it.label },
            descending = State.foodDesc,
            onPick = { State.foodSort = it },
            onToggleDirection = { State.foodDesc = !State.foodDesc },
        )
    }

    if (foods.isEmpty()) {
        item {
            /*
             * 空列表要分三种情况说清楚，不能一律写"没有匹配的食物"。
             *
             * 这三种情况用户要做的事完全不同：库是空的要先去加菜；筛选把菜滤掉了要点"全部"；
             * 搜索词没命中要改词。写同一句话的结果就是用户站在一个有菜的页面上，
             * 却被告知"没有匹配的食物"，然后不知道下一步点哪里（现场踩过）。
             */
            val hasFilter = State.category != "全部" || State.query.isNotEmpty()
            when {
                (State.data?.foods ?: emptyList()).isEmpty() -> Column {
                    EmptyHint(
                        title = "菜品库还是空的",
                        detail = "用下面的「拍照识别菜品」或「＋ 新增菜品」加几道菜，" +
                            "之后就能在这里挑这一餐吃什么。菜品保存在本机，离线也能用。",
                    )
                    // 直接给一个"从服务器拉回来"的入口：换了账号、或换了手机时最常用
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                    ) {
                        PillButton("从服务器导入菜品") { a.importDishesFromServer() }
                    }
                }
                hasFilter -> EmptyHint(
                    title = "当前筛选下没有菜品",
                    detail = "菜品库里有 ${State.data?.foods?.size ?: 0} 道菜，只是不符合现在的筛选条件" +
                        (if (State.query.isNotEmpty()) "（搜索词「${State.query}」）" else "") +
                        "。把分类切回「全部」或清空搜索词就能看到。",
                )
                else -> EmptyHint(
                    title = "没有匹配的食物",
                    detail = "试试换个搜索词或分类。",
                )
            }
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

    // 固定尾：拍照识别 / ＋新增菜品（托盘在更下面，由宿主页面自己给）
    item {
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

/* --------------------------------------------------- p.10 收藏食物 */

/**
 * 收藏时间。存的是时间戳，`0` 表示这一行没有可用的时间
 * （v1 的库把时间存成格式化文本，升级时没解析出来的那些行）。
 */
private fun favoriteText(millis: Long?): String =
    if (millis == null || millis <= 0L) "—" else Units.dateTime(millis)

/**
 * 收藏食物。
 *
 * 数据**只来自本地库**：菜品表里的 `favorite` 标记（`Store.dishes()` 已经连收藏时间一起读出来了）。
 * 以前这里还会去碰服务器给的 `favoriteTimes` —— 那个字段现在服务器返回空对象。
 * 收藏本来就该是"我自己点的"，与服务器无关，所以离线可用。
 */
@Composable
fun FavoritesScreen(a: MainActivity) {
    val favorites = State.sortedFavorites()
    val folders = State.favoriteFolders()

    val shown = favorites
        .filter { State.category == "全部" || it.category == State.category }
        .filter { State.query.isEmpty() || it.name.contains(State.query, ignoreCase = true) }

    // 用 BounceColumn 而不是 LazyColumn：这一页通常不满一屏，需要拖动回弹
    BounceColumn(Modifier.fillMaxSize()) {
        FoodSearchField()
        CategoryChips()
        // 与菜品列表同一批维度，但方向与维度是**收藏页自己的**（State.favoriteSort）
        SortBar(
            current = State.favoriteSort,
            options = State.FavoriteSort.entries.toList(),
            label = { it.label },
            descending = State.favoriteDesc,
            onPick = { State.favoriteSort = it },
            onToggleDirection = { State.favoriteDesc = !State.favoriteDesc },
        )

        // 收藏夹：按分类汇总，点击即筛选。
        // 文件夹本身**固定按字母顺序**排列（需求要求），不受上面的维度与方向影响。
        FolderRow("全部收藏", favorites.size) { State.category = "全部" }
        folders.forEach { (category, count) ->
            FolderRow(category, count) { State.category = category }
        }

        shown.forEach { food ->
            /*
             * 整行可点 = 打开这道菜的编辑界面。
             *
             * 原来行尾挂一颗「编辑」按钮，现在去掉了：这颗按钮和"点这一行"做的是同一件事，
             * 而多一个点击目标就多一套按压反馈（点按钮有水波、点行没有），看起来像两个功能。
             * 整行可点之后，收藏列表与菜品库两处的行行为也统一了。
             */
            ListRow(
                title = food.name,
                // 收藏时间这里存的是时间戳，格式化推迟到这一刻 —— 「时间显示年 / 秒」才跟着设置走
                subtitle = "收藏时间: " + favoriteText(food.favoriteAtMs),
                onClick = { a.handleDishAction(food, DishAction.Edit) },
                leading = { CoverBox { Text(State.emojiFor(food), fontSize = 26.sp) } },
            )
        }

        if (favorites.isEmpty()) {
            EmptyHint(
                title = "还没有收藏的菜品",
                detail = "在菜品列表里点菜品右侧的「⋮ → 收藏」，收藏的菜会出现在这里。" +
                    "数据保存在本机，离线也能看。",
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
