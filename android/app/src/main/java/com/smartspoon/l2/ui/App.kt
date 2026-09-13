package com.smartspoon.l2.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspoon.l2.MainActivity
import com.smartspoon.l2.Screen
import com.smartspoon.l2.State

/**
 * 应用外壳（`MainActivity` 里剩下的那一层），结构对齐本机 Gramophone：
 *
 * ```
 * Scaffold
 * ├── LargeTopAppBar   （大标题，滚动时折叠；contentScrim = surfaceContainer）
 * ├── 内容             （各页面自己的 LazyColumn / Column）
 * └── bottomBar        （MD3 NavigationBar）
 * ```
 *
 * 阶段 3 之后这里只剩「快速开始 / 收藏食物 / 用餐记录 / 设置」四个标签页了：
 * 用餐流程那四个页面（选择本餐菜单 / 自定义本餐菜单 / 用餐中 / 用餐结果）和设置子页、
 * 统计数据一样是**独立 Activity**，各自带标题栏与自己的返回栈，不再从 `State.screen` 里分支。
 * 于是这一层不再需要 `BackHandler`、不需要给别的页面画返回箭头、也不再有托盘。
 *
 * 页面切换不再有 `render()`：所有页面状态都在 [State] 里，读写它就是重组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartSpoonApp(a: MainActivity) {
    val screen = State.screen

    // 弹窗（连接智味勺 / 用餐提醒 / 服务器地址…）画在 Scaffold 之上，
    // 所以要用 Box 包一层，让它们浮在整个界面（含标题栏、底部导航）上面。
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // 标题栏**不在**这里：它现在属于每一页自己（见 [PageWithBar]）。
            // Gramophone 的设置子页是独立 Activity，整窗（标题栏 + 内容）一起做原生转场；
            // 标题栏留在外层 Scaffold 的话，转场时只有内容在动、标题栏是"跳变"的。
            bottomBar = { AppNavigationBar(a, screen) },
        ) { padding ->
            // 两种转场，都取自 Gramophone：
            // · 换底部标签 → 整页左右滑动（对应它顶部标签的 ViewPager 翻页）
            // · 页内跳转（设置首页那一层…）→ 交叉淡入，无位移，实测约 250ms
            AnimatedContent(
                targetState = currentPage(),
                transitionSpec = { pageTransition() },
                label = "page",
                // 只让出底部栏的高度：状态栏那一条由每页自己的标题栏去处理
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) { page ->
                // 「新页压在上方不透明，旧页原地被盖住并**慢慢变暗**」：变暗用一层黑色遮罩实现。
                //
                // 判断"I 是不是正在离场的那一页"要用 transition.targetState：
                // AnimatedContent 给每块内容各建一个 Transition<EnterExitState>，
                // 进入的那页是 PreEnter→Visible，离场的那页是 Visible→PostExit。
                // 读它（可观察状态）会在转场开始时通知离场内容重组，遮罩才开始涨起来。
                // （早先读 State 里的当前页不行——离场内容不会重组，遮罩永远停在 0；
                //   也不能用 transition.animateFloat，它的端点是"initialState 值 → targetState 值"，
                //   表达不了"只有离场页才变暗"，实测会把新页压暗、方向正好相反。）
                val isOutgoing = transition.targetState == EnterExitState.PostExit
                // 只有"页内跳转"才压暗：换标签是 ViewPager 那种平移，两页都不该被压暗
                val dimMs = if (State.tab != page.tab) TAB_MS else PAGE_MS
                val dim by animateFloatAsState(
                    targetValue = if (isOutgoing && State.tab == page.tab) 0.26f else 0f,
                    animationSpec = tween(dimMs),
                    label = "oldPageDim",
                )
                Box(Modifier.fillMaxSize()) {
                    // 给每页铺一层不透明底色：各屏幕的根容器本身没有背景色，
                    // 不铺的话转场时两页会互相透视、糊在一起（新页压不住旧页）。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        PageWithBar(a, page)
                    }
                    if (dim > 0.001f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = dim))
                        )
                    }
                }
            }
        }

        // 弹窗宿主：界面里所有弹窗都由它按 State.dialog 渲染
        AppDialogs(a.dialogs)
    }
}

/**
 * 一页 = **它自己的标题栏** + 内容。
 *
 * 这是为了对齐 Gramophone 的窗口结构：它的设置子页是独立 Activity，进入时整窗
 * （含标题栏与折叠行为）作为一个整体做原生转场。
 *
 * 折叠滚动条因此也变成每页各自一份（每页一个 scrollBehavior），和"一个 Activity
 * 一套 AppBarLayout"是一回事。
 *
 * 标签页都没有"上一级"，所以这里不再画返回箭头（子页都是独立 Activity，
 * 返回由它们自己的返回栈处理）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageWithBar(a: MainActivity, page: PageKey) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(titleFor(page), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            PageContent(a, page)
        }
    }
}

/* ------------------------------------------------------- 转场的公共参数 */

/**
 * 转场时长（都取自对 Gramophone 的实测，不是估的）：
 * - 换标签：它用 ViewPager2 翻页，整页横向滑动、约 220ms
 * - 其余跳转（子页等）：它的子页是**独立 Activity**，转场是系统默认的交叉淡入，
 *   实测约 250ms（10 倍缩放下量到 ≈237ms）、**没有横向位移**
 */
private const val TAB_MS = 220
private const val PAGE_MS = 250

/**
 * 这次切换是不是「换底部标签」。
 * 换标签 → 整页横向滑动（ViewPager2 翻页）；其余 → 交叉淡入（系统 Activity 转场）。
 */
private fun isTabSwitch(from: PageKey, to: PageKey): Boolean = from.tab != to.tab

/** 新页进入的方向：+1 = 从右侧（下钻 / 往右翻标签），-1 = 从左侧（返回 / 往左翻）。 */
private fun transitionDir(from: PageKey, to: PageKey): Int =
    if (isTabSwitch(from, to)) {
        if (to.tab > from.tab) 1 else -1
    } else {
        if (pageDepth(to) >= pageDepth(from)) 1 else -1
    }

/** 页面转场。 */
private fun AnimatedContentTransitionScope<PageKey>.pageTransition(): ContentTransform =
    if (isTabSwitch(initialState, targetState)) {
        // Gramophone 顶部标签 = ViewPager2：两页一起横向平移，**都不做透明度变化**
        //（它切换得很快，实测 ≤140ms 就到位；这里取 220ms 让它不至于比它还急）
        val dir = transitionDir(initialState, targetState)
        slideInHorizontally(animationSpec = tween(TAB_MS)) { w -> dir * w } togetherWith
            slideOutHorizontally(animationSpec = tween(TAB_MS)) { w -> -dir * w }
    } else {
        // 复刻系统 Activity 转场：只有交叉淡入，没有位移。
        // 旧页**不淡出**（否则会和"变暗"互相抵消，实测那块区域反而更亮），
        // 它留在原地被新页盖住、并由内容里的黑色遮罩慢慢压暗 —— 正是你描述的效果。
        fadeIn(animationSpec = tween(PAGE_MS)) togetherWith
            slideOutHorizontally(animationSpec = tween(PAGE_MS)) { 0 }
    }

/** 各页面的标题（按页面标识取，不能读全局 State，否则两页会渲染成同一个标题）。 */
private fun titleFor(page: PageKey): String = when (page.screen) {
    is Screen.QuickStart -> "快速开始"
    is Screen.Tab -> when (page.tab) {
        1 -> "收藏食物"
        2 -> "用餐记录"
        3 -> "设置"
        else -> "快速开始"
    }
}

/* --------------------------------------------------------------- 页面路由 */

/**
 * 当前页面的完整标识。
 *
 * 转场动画要**同时**渲染「旧页」和「新页」，所以页面必须能由这个标识独立渲染出来，
 * 不能读全局的 [State]（那样新旧两页会渲染成同一页，看起来就是没有动画）。
 */
private data class PageKey(
    val screen: Screen,
    val tab: Int = 0,
    val settingsView: String = "",
    val statsPage: Int = 1,
)

private fun currentPage(): PageKey =
    PageKey(State.screen, State.tab, State.settingsView, State.statsPage)

/** 是不是某个标签的根页面（没有任何下钻）。 */
private fun isRootPage(key: PageKey): Boolean = when (key.screen) {
    is Screen.QuickStart -> true
    is Screen.Tab -> key.settingsView.isEmpty() && key.statsPage == 1
}

/** 页面深度：用于判断是「下钻」还是「返回」，决定滑入方向。 */
private fun pageDepth(key: PageKey): Int = when (key.screen) {
    is Screen.QuickStart -> 0
    is Screen.Tab -> if (isRootPage(key)) 0 else 1
}

/** 按标识渲染页面。 */
@Composable
private fun PageContent(a: MainActivity, key: PageKey) {
    // 数据还没到位（离线 / 正在读取）时统一显示离线页，和旧版行为一致
    if (State.data == null) {
        OfflineScreen(a)
        return
    }
    when (key.screen) {
        is Screen.QuickStart -> QuickStartScreen(a)
        is Screen.Tab -> TabHost(a, key.tab, key.settingsView, key.statsPage)
    }
}

/** 底部四个标签各自的页面（按标识里的字段取页，不读全局状态）。 */
@Composable
private fun TabHost(a: MainActivity, tab: Int, settingsView: String, statsPage: Int) {
    when (tab) {
        1 -> FavoritesScreen(a)
        // 统计数据的两个子页都已拆成独立 Activity：这里只剩用餐记录列表
        2 -> RecordsScreen(a)
        // 设置的四个子页已拆成独立 Activity（见 SettingsActivities.kt），
        // 这一页现在只剩设置首页
        3 -> SettingsScreen(a)
        else -> QuickStartScreen(a)
    }
}

/**
 * 底部四个标签。
 *
 * 这里**没有**用 MD3 的 `NavigationBar`：它的选中指示器是原地淡入 / 放大的，
 * 切换时看不出"移动"。要的是 Gramophone 顶部标签那种**方块滑过去**的效果
 * （`selected_chip_background`：secondaryContainer 实心、10dp 圆角、上下各留 6dp），
 * 所以自己画一层：一个滑块 + 一行图标文字，`animateDpAsState` 把滑块从旧位置送到新位置。
 */
@Composable
private fun AppNavigationBar(a: MainActivity, screen: Screen) {
    // 用餐流程的四个页面都已是独立 Activity（它们自己不带底部导航），
    // 所以主界面上的选中项就是 State.tab。
    val active = if (screen is Screen.QuickStart) 0 else State.tab
    val items = listOf(
        Triple("快速开始", Icons.Filled.Restaurant, 0),
        Triple("收藏食物", Icons.Filled.Star, 1),
        Triple("用餐记录", Icons.Filled.History, 2),
        Triple("设置", Icons.Filled.Settings, 3),
    )

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding(),
    ) {
        val itemWidth = maxWidth / items.size
        val blockX by animateDpAsState(
            targetValue = itemWidth * active,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            label = "tab-block",
        )

        // 滑块（画在图标下面一层，位置随选中项滑动）
        Box(
            Modifier
                .offset(x = blockX)
                .padding(horizontal = 6.dp)
                .padding(top = 4.dp)
                .width(itemWidth - 12.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )

        Row(Modifier.fillMaxWidth()) {
            items.forEach { (label, icon, index) ->
                val selected = active == index
                Column(
                    Modifier
                        .weight(1f)
                        // 80dp → 60dp（约原来的 3/4）：屏幕最底下那条系统手势条会让
                        // 80dp 的栏看起来「没贴底」，压掉的是上下留白，图标与文字尺寸不变。
                        .height(60.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { a.selectTab(index) },
                        )
                        .padding(top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        fontSize = 12.sp,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 离线 / 正在读取：对应旧版的 `offlineView()`。 */
@Composable
private fun OfflineScreen(a: MainActivity) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("智味勺", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        Text(
            State.loadError ?: "正在读取菜品信息…",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "服务器: ${State.server}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { a.showServerDialog() }) { Text("设置服务器地址") }
            Button(onClick = { a.reloadFromServer() }) { Text("重新读取") }
        }
    }
}
