package com.equimeal.gramo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.equimeal.gramo.Food
import com.equimeal.gramo.State
import com.equimeal.gramo.Units

/* ------------------------------------------------------------------ 度量 */

/**
 * 列表行度量，直接取自本机 Gramophone 的 `res/values/dimens.xml` 与
 * `layout/adapter_list_card.xml`：行高 75dp、封面 50dp、圆角 6dp、行内左右 6dp、
 * 封面与文字间距 18dp、标题 17sp、副标题 14sp、尾部图标按钮 48dp。
 */
object Metrics {
    val RowHeight = 75.dp
    val RowPaddingH = 6.dp
    val CoverSize = 50.dp
    val CoverCorner = 6.dp
    val Gap = 18.dp
    val IconButton = 48.dp
    val ListVerticalPadding = 8.dp
}

/* ------------------------------------------------------------ 三步进度条 */

/**
 * 餐前设置 → 用餐中 → 用餐结果。
 *
 * 这是设计稿独有的信息结构（Gramophone 里没有对应物），按你的要求保留；
 * 但配色改吃 MD3：已到达的步骤用 primary，未到达的用 outlineVariant。
 */
@Composable
fun StepBar(current: Int, modifier: Modifier = Modifier) {
    val labels = listOf("餐前设置", "用餐中", "用餐结果")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val no = index + 1
            val reached = no <= current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (no > 1) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(2.dp)
                                .background(
                                    if (no <= current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (reached) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$no",
                            fontSize = 15.sp,
                            fontWeight = if (reached) FontWeight.Bold else FontWeight.Normal,
                            color = if (reached) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (no < 3) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(2.dp)
                                .background(
                                    if (no < current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    label,
                    fontSize = 14.sp,
                    color = if (reached) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* -------------------------------------------------------------- 列表行 */

/** 一个圆角封面方块（Gramophone 用 `MaterialCardView` + filled 样式）。 */
@Composable
fun CoverBox(size: androidx.compose.ui.unit.Dp = Metrics.CoverSize, content: @Composable () -> Unit = {}) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(Metrics.CoverCorner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Gramophone 式列表行：无分隔线、行本身无底色，封面 + 标题 + 副标题 + 尾部插槽。
 * 列表里请配 `contentPadding` 让内容能滚到 app bar 下方。
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit = { CoverBox() },
    trailing: @Composable () -> Unit = {},
    minHeight: androidx.compose.ui.unit.Dp = Metrics.RowHeight,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Metrics.RowPaddingH, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 12.dp)) { leading() }
        Spacer(Modifier.width(Metrics.Gap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                title,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/* ------------------------------------------------------------ 食物行 */

/**
 * 菜品列表的一行：封面用 emoji，右侧是「加入 / 已加入」状态图标 + ⋮。
 *
 * 两个刻意的取舍：
 * - 行尾只放图标（＋ / ✓）而不是带字胶囊 —— 文字按钮太宽，副标题会被挤成「能量密度: 2.1…」；
 * - **整行是唯一的"加入 / 取消加入"入口**，那颗圆形图标只是**状态指示器**：
 *   它不接收点击（`enabled = false`，理由见下面的注释），因此不存在
 *   "点圆形按钮"和"点整行"两套按压反馈。勾选态仍由 contentDescription 提供给读屏。
 */
@Composable
fun FoodRow(
    food: Food,
    showToggle: Boolean,
    onToggle: (() -> Unit)? = null,
    onMore: ((DishAction) -> Unit)? = null,
) {
    val selected = State.selected.contains(food.id)
    ListRow(
        title = food.name,
        subtitle = "能量密度: ${Units.density(food.density)} · 食用 ${food.times} 次",
        // 整行可点 → 加入 / 取消加入这一道菜
        onClick = if (showToggle) ({ onToggle?.invoke() }) else null,
        leading = { CoverBox { Text(State.emojiFor(food), fontSize = 26.sp) } },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showToggle) {
                    /*
                     * 纯指示器：`enabled = false` 让这颗按钮**不再处理点击**，水波随之消失。
                     *
                     * 为什么用 `enabled = false` 而不是 `onClick = null`：Material3 1.3.1 的
                     * `FilledTonalIconButton` 把 `onClick` 声明成**非空必填**（`Function0<Unit>`），
                     * 传不了 null（`javap` 查过 classes.jar 确认）。
                     * 禁用只影响**内容色**（图标），容器仍是 `secondaryContainer`，外观与原来一致。
                     *
                     * 仍然用**同一个节点**在内容里换图标：选中态切换时不重建节点，图标替换更干脆。
                     */
                    FilledTonalIconButton(onClick = {}, enabled = false) {
                        if (selected) {
                            Icon(Icons.Filled.Check, contentDescription = "已添加")
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = "添加到菜单")
                        }
                    }
                }
                if (onMore != null) {
                    DishMenuButton(favorite = food.favorite, onSelect = onMore)
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
    )
}

/**
 * 菜品行的「⋮」：收藏 / 编辑 / 删除，用 MD3 的 `DropdownMenu`。
 *
 * 收藏那一项**按当前状态显示**："已收藏"就写「取消收藏」，没收藏就写「收藏」。
 * （原来两种状态都写「收藏 / 取消收藏」，用户得先点开才知道会发生什么。）
 */
@Composable
fun DishMenuButton(favorite: Boolean, onSelect: (DishAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (favorite) "取消收藏" else "收藏") },
                onClick = { open = false; onSelect(DishAction.ToggleFavorite) },
            )
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = { open = false; onSelect(DishAction.Edit) },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = { open = false; onSelect(DishAction.Delete) },
            )
        }
    }
}

/**
 * 用餐中的「当前勺中食物」。
 *
 * 和设置里「热量单位 / 重量单位」是**同一种控件**：一颗显示当前值的胶囊，点开是弹出列表；
 * 区别只在列表内容——这里是这一餐的食物（当前那项打勾），最后固定追加一项「＋ 添加食物」。
 */
@Composable
fun SpoonFoodChip(
    current: String,
    foods: List<Food>,
    onPick: (Food) -> Unit,
    onAddFood: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text("$current  ▾") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            foods.forEach { food ->
                DropdownMenuItem(
                    text = { Text("${State.emojiFor(food)}  ${food.name}") },
                    leadingIcon = if (food.name == current) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "当前勺中食物",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onPick(food)
                    },
                )
            }
            HorizontalDivider()
            // 最后一项：去选菜页加菜（期间自动暂停记录，加完回来恢复）
            // 文字里不再带「＋」——加号已经由 leadingIcon 给出了，否则会出现两个加号
            DropdownMenuItem(
                text = { Text("添加食物") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    open = false
                    onAddFood()
                },
            )
        }
    }
}

enum class DishAction { ToggleFavorite, Edit, Delete }

/* ------------------------------------------------------------ 设置行 */

/** 设置行：标题 + 说明 + 右侧控件（开关 / 胶囊按钮 / 值）。 */
@Composable
fun ConfigRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    /** true = 这一行的语义是"开关"（读屏读成开关、可切换），而不是普通按钮。 */
    toggleable: Boolean = false,
    checked: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick == null -> Modifier
                    toggleable -> Modifier.toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = { onClick() },
                    )
                    else -> Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/**
 * 开关行。
 *
 * **整行是唯一的切换入口**：点标题、点说明、点右侧开关的任意位置，效果完全一样。
 *
 * 两个刻意的设计：
 * - 行用 `toggleable`（不是 `clickable`）并带 `Role.Switch`：读屏会把它读成
 *   「开关，已开启/已关闭」，双击切换 —— 把语义放在行上，整行才是一个真正的开关控件。
 * - 右侧那颗 `Switch` 传 `onCheckedChange = null`，也就是**纯指示器**：
 *   它自己不再接收点击、不再是第二个点击目标，因此也就没有"点开关"和"点行"
 *   两种不同的按压反馈。选中态变化时滑块照常滑动（那是状态动画，不是按压反馈）。
 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ConfigRow(
        title,
        subtitle,
        onClick = { onCheckedChange(!checked) },
        toggleable = true,
        checked = checked,
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/* ------------------------------------------- Gramophone 式设置行 */

/**
 * Gramophone 的设置行。数值全部照它的布局 XML 抠出来
 * （`res/layout/preference_basic.xml`，开关行见 `preference_switch.xml`、
 * 值行见 `preference_dropdown_md.xml`）：
 *
 * - 透明底（它套了一层 `materialCardViewFilledStyle`，但背景色设成 transparent）
 * - `paddingHorizontal 24dp`、`paddingVertical 20dp`
 * - 前面一个 **24dp 图标**（tint = colorOnSurface），图标末尾再留 24dp
 * - 标题 **19sp**（colorOnSurface）；副标题 **15sp**（colorOnSurfaceVariant），上方留 1dp
 * - **没有分隔线、没有尾部箭头**：整行可点，反馈只靠水波
 *
 * 和本应用「曲库式」的 [ListRow]（75dp 行、50dp 封面）是两套：Gramophone 的设置页
 * 和它的列表页用的本来就不是同一套行。
 */
@Composable
fun PrefRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /** true = 这一行的语义是"开关"（读屏读成开关、可切换），而不是普通按钮。 */
    toggleable: Boolean = false,
    checked: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick == null -> Modifier
                    toggleable -> Modifier.toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = { onClick() },
                    )
                    else -> Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    summary,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 有尾部控件时，文字列与控件之间留 16dp（它的 preference_switch.xml 里的 layout_marginEnd）
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/**
 * 开关设置行（Gramophone 的 `preference_switch.xml`：右侧就是一整颗 MD3 开关）。
 *
 * 与 [SwitchRow] 一样：**整行是唯一的切换入口**，行带 `Role.Switch` 语义，
 * 右侧那颗开关是纯指示器（`onCheckedChange = null`）—— 它不再是独立点击目标，
 * 所以不存在"点开关"和"点行"两套按压反馈。
 */
@Composable
fun PrefSwitchRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    PrefRow(
        icon = icon,
        title = title,
        summary = summary,
        onClick = { onCheckedChange(!checked) },
        toggleable = true,
        checked = checked,
        trailing = {
            Switch(checked = checked, onCheckedChange = null)
        },
    )
}

/**
 * 分类标题（Gramophone 的 `preference_category_md.xml`）：
 * `paddingHorizontal 24dp` + `paddingTop 12dp`、**primary 色**、字重 600。
 */
@Composable
fun PrefCategory(title: String) {
    Text(
        title,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 值行右侧的「下拉」外观：Gramophone 那里是一个 `Spinner`（显示当前值 + 小箭头，没有背景），
 * 所以这里也不用胶囊，只用文字 + 箭头。
 *
 * 它**自己不响应点击** —— 弹出的开关交给整行（见 [PrefSelectRow]），
 * 这样点标题、点说明、点右边的值都能把列表打开。
 */
@Composable
private fun DropdownValue(value: String) {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 带下拉选择的设置行（对应 Gramophone 的值行 `preference_dropdown_md.xml`）。
 *
 * **整行可点**：`PrefRow` 的 `onClick` 负责把列表打开，右侧的值只是显示。
 * 弹出位置锚定在右侧那个值上（`DropdownMenu` 挂在它的 `Box` 里），
 * 所以点左边的标题也能弹出到该在的位置。
 */
@Composable
fun PrefSelectRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    PrefRow(icon, title, summary, onClick = { open = true }, trailing = {
        Box {
            DropdownValue(value)
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
    })
}

/* ---------------------------------------------------------- 小控件 */

/** 胶囊按钮（对应旧版的 pillButton）。 */
@Composable
fun PillButton(
    text: String,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    if (filled) {
        FilledTonalButton(onClick = onClick) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text) }
    }
}

/** 小节标题（卡片分组）。 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** 页面内的说明文字。 */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/**
 * 空状态：一句结论 + 一句"怎么做才会有内容"。
 *
 * 为什么专门做一个组件：**去掉模板数据之后，"空"会变成常态**（新装的库没有菜品、
 * 没有收藏、没有用餐记录，设备列表里也没有勺子）。空状态如果只是一片白、
 * 或者只写"暂无数据"，用户不知道下一步该点哪里 —— 而这恰恰是最需要指路的时候。
 *
 * 所以每个空状态都必须回答两个问题：现在是什么状态、我要做什么才会变。
 */
@Composable
fun EmptyHint(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 排序条：左边是**排序维度**（下拉），右边是**正序 / 倒序**（点一下切换）。
 *
 * 三个列表（收藏食物 / 用餐记录 / 菜品列表）共用它，所以维度用泛型 [T] 传：
 * 每个列表有自己的排序枚举（`State.FoodSort` / `State.FavoriteSort` / `State.RecordSort`），
 * 但它们都提供中文 `label`，界面只认这个。
 *
 * 为什么"维度"做成下拉、而不是像旧版那样一个写死的胶囊：需求里每张列表都有 4 个维度可选
 * （字母 / 时间 / 次数 / 密度…），四个胶囊横着排在窄屏上会挤成两行；下拉只占一格。
 * 方向则相反 —— 它只有两个值、又需要频繁切换，做成一个能一眼看出当前状态的按钮。
 */
@Composable
fun <T> SortBar(
    current: T,
    options: List<T>,
    label: (T) -> String,
    descending: Boolean,
    onPick: (T) -> Unit,
    onToggleDirection: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Box {
            FilterChip(
                selected = false,
                onClick = { open = true },
                label = { Text("≡ ${label(current)}  ▾") },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        onClick = {
                            open = false
                            onPick(option)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = descending,
            onClick = onToggleDirection,
            label = { Text(if (descending) "倒序" else "正序") },
        )
    }
}

/* ------------------------------------------------------------ 滚动容器 */
/**
 * 可滚动的内容列，**内容不满一屏时也保留拖动回弹**。
 *
 * 为什么需要它：Compose 的滚动容器在「内容不足一屏」（maxValue == 0）时压根不进入滚动会话，
 * 于是既拖不动、也拿不到平台自带的边缘拉伸回弹——实测「快速开始」拖动时画面毫无反应，
 * 而内容超出一屏的「用餐记录」有回弹，就是这个原因。
 *
 * 这里给内容一个「视口高度 + 一点余量」的最小高度，让滚动区间始终存在，
 * 拖动就能拿到与长列表完全一致的回弹反馈（等价于旧版 ScrollView 的 OVER_SCROLL_ALWAYS）。
 */
@Composable
fun BounceColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        // 多出的这几 dp 只是为了造出一点真实滚动区间，肉眼看不出来
        val minContentHeight = maxHeight + 2.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minContentHeight)
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}
