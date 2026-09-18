package com.smartspoon.l2.ui

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspoon.l2.MainActivity
import com.smartspoon.l2.MealRecord
import com.smartspoon.l2.State
import com.smartspoon.l2.Units
import kotlin.math.floor
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.filled.BarChart

/*
 * 用餐记录（p.11）/ 统计数据（p.12、p.13）/ 用餐中（p.07）/ 用餐结果（p.08）四页。
 *
 * 排布沿用 AppScreens.kt：页面内容整体在可折叠的大标题栏下滚动。标题栏一律由**外层**提供 ——
 * 用餐记录在 `MainActivity` 的标签里、统计数据在 `StatsActivity`、用餐中与用餐结果各自是
 * 独立 Activity（见 MealFlowActivities.kt，外壳见 PageShell.kt），所以这四页都不画自己的
 * 页面标题、也不画返回按钮。「用餐中」「用餐结果」按设计稿保留三步进度条，
 * 它作为滚动内容的第一项。
 *
 * 行一律用 Components.kt 的共享词汇（ListRow / ConfigRow / SectionTitle / PillButton /
 * StepBar）：不画分隔线、不给行铺底色；配色只取 MaterialTheme.colorScheme，
 * 旧的品牌绿 / 青色 / 黄色硬编码一律不再出现。
 */

/* --------------------------------------------------- p.11 用餐记录 */

/**
 * 用餐记录里的一处时间。
 *
 * 优先用时间戳**现格式化**：这样「设置 → 杂项」里两个时间开关一改，这一页当即就变，
 * 不需要重读数据库。没有时间戳的老数据（走服务器 bootstrap 那条路径）退回库里读出来的文本。
 */
private fun mealTime(millis: Long, fallback: String): String =
    if (millis > 0L) Units.dateTime(millis) else fallback.ifBlank { "—" }

/**
 * 用餐记录：顶部是「统计数据 / 排序」工具条，下面每条用餐记录一行。
 *
 * 旧版一条记录是一张白卡片，里面塞了「#编号 + 菜单名 + 食物 chip 两列 + 三行合计 + 用餐时间」。
 * Gramophone 的行只有「封面 + 标题 + 两行副标题」三级信息，所以这里拆成两行来表达同一份数据：
 * 主行放记录身份与本次菜品（封面用第一道菜的 emoji，副标题沿用菜品列表页 `甲 / 乙 / 丙` 的写法），
 * 紧跟的 ConfigRow 放合计与起止时间 —— 两者都不铺底色，看起来仍是一条记录。
 */
@Composable
fun RecordsScreen(a: MainActivity) {
    val records = State.data?.records ?: emptyList()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        /*
         * 工具条对应旧版顶部的三个 pillButton。窄屏上「统计数据 + 按用餐时间排序 + 正序」
         * 三个胶囊加起来会超过一行宽度，所以让它横向滚动，宁可可滚也不要把「正序」裁掉。
         */
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { a.startActivity(Intent(a, StatsActivity::class.java)) },
                    label = { Text("统计数据") },
                )
                // 旧版这一项同样是空动作（记录恒按用餐时间排列）
                FilterChip(selected = false, onClick = {}, label = { Text("≡ 按 用餐时间 排序") })
                FilterChip(
                    selected = State.sortDesc,
                    onClick = { State.sortDesc = !State.sortDesc },
                    label = { Text(if (State.sortDesc) "正序" else "倒序") },
                )
            }
        }

        items(records) { record ->
            Column {
                val foods = record.foods.mapNotNull { State.food(it) }
                ListRow(
                    title = "#${record.no}  ${record.menu}",
                    subtitle = foods
                        .joinToString("  ") { "${State.emojiFor(it)} ${it.name}" }
                        .takeIf { it.isNotEmpty() },
                    onClick = { a.showMealDetail(record) },
                    leading = {
                        CoverBox { Text(State.emojiFor(foods.firstOrNull()), fontSize = 24.sp) }
                    },
                )
                /*
                 * 合计与起止时间。ConfigRow 的标题没有 maxLines，长文本会自然折行，
                 * 所以「总口数 / 总重量 / 总热量」与「用餐时间 起 至 止」都能完整显示，
                 * 不会像副标题那样被截断成省略号。
                 *
                 * 重量与热量走 Units（跟着「设置 → 杂项」的单位走）；
                 * 时间由 startedAt / endedAt 现格式化（跟着两个时间开关走），
                 * 而不是用库里读出来时就算好的字符串 —— 那样改完设置这一页不会变。
                 */
                ConfigRow(
                    title = "总口数: ${record.bites} · 总重量: ${Units.weight(record.weight)} · " +
                        "总热量: ${Units.energy(record.energy)}",
                    subtitle = "用餐时间: ${mealTime(record.startedAt, record.start)} 至 " +
                        "${mealTime(record.endedAt, record.end)}",
                )
            }
        }

        if (records.isEmpty()) {
            item {
                BodyText("还没有用餐记录，完成一次用餐后这里会出现记录。")
            }
        }
    }
}

/* --------------------------------------- p.12 / p.13 统计数据 */

/**
 * 统计数据两页：第 1 页是汇总，第 2 页是折线图 + 五个统计设置。
 *
 * 旧版顶部有「用餐记录」标题和「‹ 返回」小按钮，现在两者都由 App.kt 的标题栏承担，
 * 所以这里只剩分页点（旧版的两个圆点，点一下切页）。
 */
@Composable
fun StatsScreen(ctx: Context) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
            item { MealChart() }
            item {
                SelectRow(
                    title = "x轴:  ${State.axisX}",
                    options = listOf("用餐完成时间", "用餐开始时间"),
                    value = State.axisX,
                ) { State.axisX = it }
            }
            item {
                SelectRow(
                    title = "y轴:  ${State.axisY}",
                    options = listOf("摄入能量", "摄入重量", "记录口数"),
                    value = State.axisY,
                ) { State.axisY = it }
            }
            item {
                SelectRow(
                    title = "统计图样式",
                    subtitle = "更改统计图的呈现方式",
                    options = listOf("折线统计图", "柱状统计图"),
                    value = State.chartStyle,
                ) { State.chartStyle = it }
            }
            item {
                SelectRow(
                    title = "显示范围",
                    subtitle = "更改统计图展示数据的范围",
                    options = listOf("全部", "近 7 次", "近 5 次"),
                    value = State.chartRange,
                ) { State.chartRange = it }
            }
            item {
                // 旧版 ChartView 只实现了线性刻度：这一项同样是「记录下来但不改变绘制」，保持一致
                SelectRow(
                    title = "坐标轴间距",
                    subtitle = "更改坐标轴数值分布",
                    options = listOf("线性", "对数"),
                    value = State.axisScale,
                ) { State.axisScale = it }
            }

        // 入口：进入第 12 页「统计数据汇总」。和设置页一样，是一行可点的条目。
        item {
            PrefRow(
                icon = Icons.Filled.BarChart,
                title = "统计数据汇总",
                summary = "总用餐时间、总口数、总摄入重量与能量",
                onClick = { ctx.startActivity(Intent(ctx, StatsSummaryActivity::class.java)) },
            )
        }
    }
}

/** 统计值（旧版 statRow 右侧的 19sp 粗体值）。 */
/** 分页点：两个 8dp 圆点，选中用 primary，未选中用 outlineVariant。 */
/**
 * 带下拉选择的一行（对应旧版的 `configRow` + `selectBox`）。
 *
 * 旧版 selectBox 弹出的是系统 PopupMenu；这里用 MD3 的 DropdownMenu，
 * 选项写回 State 之后界面自动重组，不再需要旧版那次 `renderContentOnly()`。
 *
 * **整行可点**：`ConfigRow` 的 `onClick` 负责把列表打开，右侧那个值只负责显示，
 * 自身不再处理点击 —— 否则点在值和点在行上会走两条不同的路径。
 */
@Composable
private fun SelectRow(
    title: String,
    subtitle: String? = null,
    options: List<String>,
    value: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ConfigRow(title, subtitle, onClick = { open = true }) {
        Box {
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("$value  ▾", fontSize = 15.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
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
}

/**
 * 折线统计图（p.13）：进入页面时线条从左往右生长。
 *
 * 从旧的 `ChartView`（自定义 View + Canvas）逐个 API 平移过来：
 * 内边距、y 轴刻度线、斜排 -32° 的 x 轴日期、只画到生长进度处的折线（末段插值）
 * 都与原来一致；「柱状统计图」那个分支也一并保留（旧的样式下拉里有这一项）。
 *
 * 轴上的文字仍用原生 `Paint` 画：Compose 的 `drawText` 需要 TextMeasurer，
 * 而这里还要 `translate + rotate(-32°)` 斜排日期，直接操作 nativeCanvas 最省事，
 * 也就不用为文字单独搭一层 Compose 布局。文字颜色取自主题（onSurfaceVariant）。
 */
@Composable
private fun MealChart() {
    val chart = State.data?.chart ?: return
    val scheme = MaterialTheme.colorScheme
    // 生长进度 0 → 1，与旧版 animateProgress(800ms, Motion.standard) 对齐
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = true } }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .padding(horizontal = 6.dp),
    ) {
        val padLeft = 42.dp.toPx()
        val padRight = 14.dp.toPx()
        val padTop = 36.dp.toPx()
        val padBottom = 46.dp.toPx()
        val plotW = size.width - padLeft - padRight
        val plotH = size.height - padTop - padBottom
        if (plotW <= 0f || plotH <= 0f) return@Canvas
        val yMax = (chart.yTicks.maxOrNull() ?: 1200).toFloat().coerceAtLeast(1f)

        labelPaint.color = scheme.onSurfaceVariant.toArgb()
        labelPaint.textSize = 9.sp.toPx()

        // y 轴刻度线 + 刻度值
        chart.yTicks.forEach { tick ->
            val y = padTop + plotH - plotH * tick / yMax
            drawLine(
                color = scheme.outlineVariant,
                start = Offset(padLeft, y),
                end = Offset(padLeft + plotW, y),
                strokeWidth = 1.dp.toPx(),
            )
            labelPaint.textAlign = Paint.Align.RIGHT
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(tick.toString(), padLeft - 6.dp.toPx(), y + 4.dp.toPx(), labelPaint)
            }
        }

        val all = chart.points
        val points = when (State.chartRange) {
            "近 7 次" -> all.takeLast(7)
            "近 5 次" -> all.takeLast(5)
            else -> all
        }
        // 少于两个点连不成线：与旧版一样，此时只留下网格
        if (points.size < 2) return@Canvas

        val stepX = plotW / (points.size - 1)
        fun px(index: Int) = padLeft + stepX * index
        fun py(index: Int) = padTop + plotH - plotH * points[index].y / yMax

        val visible = progress.value * (points.size - 1)
        val lastFull = floor(visible.toDouble()).toInt().coerceIn(0, points.size - 1)
        val partial = visible - lastFull

        if (State.chartStyle == "柱状统计图") {
            val barWidth = stepX * 0.5f
            val baseY = padTop + plotH
            for (index in 0..lastFull) {
                val grow = if (index < lastFull) 1f else partial
                if (grow <= 0f) continue
                val topY = baseY - (baseY - py(index)) * grow
                drawRoundRect(
                    color = scheme.primary,
                    topLeft = Offset(px(index) - barWidth / 2f, topY),
                    size = Size(barWidth, baseY - topY),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
        } else {
            val path = Path()
            path.moveTo(px(0), py(0))
            for (index in 1..lastFull) path.lineTo(px(index), py(index))
            // 最后一段按进度插值，线条像是「长」出来的
            if (lastFull < points.size - 1 && partial > 0f) {
                path.lineTo(
                    px(lastFull) + stepX * partial,
                    py(lastFull) + (py(lastFull + 1) - py(lastFull)) * partial,
                )
            }
            drawPath(
                path = path,
                color = scheme.primary,
                style = Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            for (index in 0..lastFull) {
                drawCircle(scheme.primary, radius = 2.4.dp.toPx(), center = Offset(px(index), py(index)))
            }
        }

        // x 轴日期斜排
        labelPaint.textAlign = Paint.Align.RIGHT
        points.forEachIndexed { index, point ->
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(
                    padLeft + stepX * index + stepX * 0.2f,
                    size.height - padBottom + 14.dp.toPx(),
                )
                native.rotate(-32f)
                native.drawText(point.x, 0f, 0f, labelPaint)
                native.restore()
            }
        }
        labelPaint.textAlign = Paint.Align.LEFT
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(chart.yLabel, padLeft - 34.dp.toPx(), 14.dp.toPx(), labelPaint)
        }
    }
}

/* --------------------------------------------------- p.07 用餐中 */

/**
 * 用餐中：实时读数来自 [State.meal]（服务器轮询与计时器写入的就是它，字段都是可观察的，
 * 所以这里读一次就会自动跟着变，不再需要旧版的 renderContentOnly）。
 *
 * 底部四个动作按钮：旧版是一行四个等宽彩色方块，MD3 下四个按钮并排会超出屏宽
 * （每个至少 58dp 宽 + 24dp 内边距），所以排成 2×2，动作与文案完全不变。
 * 这一页没有任何底部导航（它就是 `LiveActivity` 本身，不带标签栏），因此动作区钉在屏幕底部。
 */
@Composable
fun LiveScreen(a: MealFlowHost) {
    val meal = State.meal
    val online = State.deviceOnline
    val connected = State.connectedDevice()?.name ?: "未连接"

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item { StepBar(current = 2) }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (meal.paused) "已暂停" else "正在记录",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("已用餐时长: ${meal.minutes}分钟", fontSize = 17.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (online) "勺子数据来自服务器（模拟设备）" else "等待服务器勺子数据…",
                        fontSize = 12.5.sp,
                        color = if (online) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("当前已连接:", fontSize = 17.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(connected, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }

            item {
                Column {
                    // 当前勺中食物：和「杂项 → 热量单位」同一种弹出式列表——
                    // 平时只占一行显示当前值，点开才列出这一餐的食物，最后一项是「＋ 添加食物」。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "当前勺中食物:",
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        SpoonFoodChip(
                            current = meal.food,
                            foods = State.selected.mapNotNull { State.food(it) },
                            onPick = { a.chooseSpoonFood(it) },
                            onAddFood = { a.addFoodDuringMeal() },
                        )
                    }

                    // 重量与热量一律走 Units，跟着「设置 → 杂项」的单位走
                    LiveValueRow("当前勺中重量:", Units.weight(meal.spoonWeight.toDouble()))
                    LiveValueRow("当前勺中热量:", Units.energy(meal.spoonEnergy))
                    Spacer(Modifier.height(20.dp))
                    LiveValueRow("已记录口数:", meal.bites.toString())
                    LiveValueRow("已记录总重量:", Units.weight(meal.totalWeight.toDouble()))
                    LiveValueRow("已记录总热量:", Units.energy(meal.totalEnergy))
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { a.recordBite() },
                        modifier = Modifier.weight(1f),
                    ) { Text("记录一口") }
                    OutlinedButton(
                        onClick = { a.exitMeal() },
                        modifier = Modifier.weight(1f),
                    ) { Text("退出记录") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            meal.paused = !meal.paused
                            a.toast(if (meal.paused) "已暂停记录" else "继续记录")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (meal.paused) "继续记录" else "暂停记录") }
                    Button(
                        onClick = { a.finishMeal() },
                        modifier = Modifier.weight(1f),
                    ) { Text("结束用餐") }
                }
            }
        }
    }
}

/**
 * 一行实时读数（`键: 值`，值加粗）。
 *
 * 值变化时轻轻脉冲一下（旧的 `View.pulse()`：缩到 1.06 再弹回，260ms）。
 * 旧版是按「本次刷新是不是实时刷新」来决定的（`!a.animateLists`），
 * Compose 里没有这个整页重建的标记，改成直接盯住值本身变化 —— 语义一样，还少一个全局状态。
 */
@Composable
private fun LiveValueRow(key: String, value: String) {
    val scale = remember { Animatable(1f) }
    var firstPass by remember { mutableStateOf(true) }
    LaunchedEffect(value) {
        if (firstPass) {
            firstPass = false
            return@LaunchedEffect
        }
        scale.snapTo(1.06f)
        scale.animateTo(1f, animationSpec = tween(260, easing = FastOutSlowInEasing))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.scale(scale.value),
        )
    }
}

/* --------------------------------------------------- p.08 用餐结果 */

/**
 * 用餐结果：本餐四项数据可以点开「修正数据」弹窗改（弹窗由宿主渲染，
 * 这里只负责调用宿主 `ResultActivity` 的方法），下面两项由这四项算出来，不可修改。
 *
 * 可修改的值用 primary 着色、整行可点（旧版是给值套一个浅色底的小方块）；
 * 「完成」按旧版留在滚动内容的末尾，而不是钉在底部。
 */
@Composable
fun ResultScreen(a: MealFlowHost) {
    val result = State.result

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { StepBar(current = 3) }

        item {
            Text(
                "用餐结果已保存到用餐记录中。(#${result.savedNo})",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("单击数据以修正", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Spacer(Modifier.weight(1f))
                PillButton("点击查看每口详细数据") { a.showBiteDetail() }
            }
        }

        item { SectionTitle("本餐数据:") }
        item {
            ResultValueRow("用餐时长:", "${result.minutes} 分钟") { a.editResultValue("duration") }
        }
        item {
            ResultValueRow("已记录口数:", result.bites.toString()) { a.editResultValue("bites") }
        }
        item {
            ResultValueRow("已记录总重量:", Units.weight(result.weightGrams)) {
                a.editResultValue("weight")
            }
        }
        item {
            ResultValueRow("已记录总热量:", Units.energy(result.energyKj)) {
                a.editResultValue("energy")
            }
        }

        item { SectionTitle("以下数据为上述数据计算得到:") }
        item {
            Text(
                "不支持修改",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp),
            )
        }
        item { ResultValueRow("平均每口重量:", Units.weight(result.avgWeightGrams)) }
        item { ResultValueRow("平均每口热量:", Units.energy(result.avgEnergyKj)) }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "单击 完成 以保存你的数据修改",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { a.doneResult() }) { Text("完成") }
            }
        }
    }
}

/** 结果页的「键: 值」行；传了 onEdit 就是可修改的那四项。 */
@Composable
private fun ResultValueRow(key: String, value: String, onEdit: (() -> Unit)? = null) {
    ConfigRow(
        title = key,
        onClick = onEdit,
        trailing = {
            Text(
                value,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = if (onEdit != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

/* ------------------------ p.12 统计数据汇总（「关于手机」式列表） */

/**
 * 第 12 页：统计数据汇总。
 *
 * 样式对齐原生设置里的「关于手机」：一列平铺的行，**每行上方用大号字体显示类别**
 * （如「总用餐时间」），**下方显示具体数据**（如「15小时36分钟」）；没有图标、没有分隔线。
 * 它现在是独立 Activity，入口在第 13 页（统计数据）底部。
 */
@Composable
fun StatsSummaryScreen() {
    val stats = State.data?.stats ?: emptyList()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(stats) { (key, value) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Text(
                    key,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (stats.isEmpty()) {
            item { BodyText("还没有用餐数据，完成一次用餐后这里会出现汇总。") }
        }
    }
}