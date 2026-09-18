package com.smartspoon.l2.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.smartspoon.l2.State

/**
 * 「一页 = **它自己的标题栏** + 内容」的公共外壳。
 *
 * 这是阶段 2/3 一路拆下来的那块壳（原先叫 `BaseSettingsActivity`）：设置子页、统计数据、
 * 以及本阶段的用餐流程四个页面都是**独立 Activity**，于是：
 *
 * - 转场由系统负责（原生 Activity 转场），整窗（标题栏 + 折叠行为 + 内容）一起动；
 * - 系统返回键由 Activity 返回栈处理（`goBack()` 默认就是 `finish()`），
 *   不再需要 `App.kt` 里那套应用内 BackHandler；
 * - 每页一个 `scrollBehavior`，与「一个 Activity 一套 AppBarLayout」是一回事。
 *
 * 子类只声明标题与内容；需要底部常驻区域（加菜页的托盘）就覆盖 [BottomBar]，
 * 需要在整页之上挂弹窗就覆盖 [Overlays]。
 */
abstract class BasePageActivity : ComponentActivity() {

    /** 标题栏文字。 */
    abstract val pageTitle: String

    /** 页面内容（在标题栏下面）。 */
    @Composable
    abstract fun Page()

    /**
     * 用普通高度的标题栏。
     *
     * 加菜页同时钉着「步骤条 + 搜索 + 分类 + 排序 + 底部按钮 + 托盘」，
     * 再用 176dp 的大标题栏，列表在首屏就一点位置都不剩了 —— 与 `App.kt` 里的说明一致。
     */
    open val compactBar: Boolean get() = false

    /** 左上角要不要返回箭头（「用餐中」没有上一级，用它）。 */
    open val showBackArrow: Boolean get() = true

    /** 钉在底部的区域。 */
    @Composable
    open fun BottomBar() {}

    /** 画在整页最上层的东西（弹窗）。 */
    @Composable
    open fun Overlays() {}

    /** 系统返回键与左上角箭头统一走这里；默认就是关掉这一页。 */
    protected open fun goBack() {
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 和 MainActivity 一样走 edge-to-edge，否则子页的状态栏会是黑条、与主界面不一致
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { goBack() }
        setContent {
            SmartSpoonTheme {
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                Scaffold(
                    /*
                     * 折叠联动**只在大标题栏时才挂到 Scaffold 上** —— 这是个很容易踩的坑：
                     *
                     * `TopAppBarScrollBehavior` 的 `heightOffsetLimit` 默认是 `-Float.MAX_VALUE`，
                     * 只有某个 AppBar 真正把这个 behavior 接过去之后，它才会被设成"可折叠的那段高度"。
                     * 普通高度的 `TopAppBar` 是**钉住**的、不吃这个 behavior，limit 就一直是默认值；
                     * 于是 `onPreScroll` 里那句 `coerceIn(heightOffsetLimit, heightOffset)` 会把
                     * **列表所有向下的滚动全部吞掉**（available.y < 0 时它把整个 delta 都当成自己的），
                     * 表现就是「加菜页怎么划都不动」——列表自己根本收不到手势。
                     *
                     * 所以钉住标题栏的页面干脆不挂 nestedScroll：内容自己就能滚，
                     * 也不需要和标题栏联动。
                     */
                    modifier = if (compactBar) {
                        Modifier
                    } else {
                        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    },
                    topBar = {
                        if (compactBar) {
                            TopAppBar(
                                title = {
                                    Text(pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                navigationIcon = { BackArrow() },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            )
                        } else {
                            LargeTopAppBar(
                                title = {
                                    Text(pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                scrollBehavior = scrollBehavior,
                                navigationIcon = { BackArrow() },
                                colors = TopAppBarDefaults.largeTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            )
                        }
                    },
                    bottomBar = { BottomBar() },
                ) { inner ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(inner)
                    ) {
                        Page()
                    }
                }

                Overlays()
            }
        }
    }

    @Composable
    private fun BackArrow() {
        if (!showBackArrow) return
        IconButton(onClick = { goBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
    }

    override fun onDestroy() {
        // 这一页是被关掉的（不是配置变化）时，顺手清掉它留下的弹窗，
        // 免得返回到的下一页顶上还挂着上一个 Activity 的弹窗。
        if (isFinishing) State.dialog = null
        super.onDestroy()
    }
}
