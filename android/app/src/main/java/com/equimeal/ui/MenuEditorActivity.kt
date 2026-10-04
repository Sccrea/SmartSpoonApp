package com.equimeal.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import com.equimeal.AppCore
import com.equimeal.MenuDef
import com.equimeal.State

/*
 * 菜单编辑页的 Activity：新建（不带 extra）或修改一份已有菜单（带 `menu_id`）。
 *
 * 它继承 [BaseMealActivity]（和「自定义本餐菜单」同一个壳），因为这一页就是"选菜页 + 保存"：
 * 底部托盘、步骤条、拍照识别加菜、菜品 ⋮ 菜单全部照旧可用。
 *
 * 入口有两处：
 * - 「设置 → 菜单」页点某份菜单 → 改它；
 * - 「使用现有菜单快速开始」底部的「＋ 新建菜单」→ 建一份新的。
 */

/** 用这个 extra 传"要编辑哪份菜单"；不传 = 新建。 */
const val EXTRA_MENU_ID = "menu_id"

class MenuEditorActivity : BaseMealActivity(), MenuEditorHost {

    /** 要编辑的菜单；null = 新建。 */
    private val editingMenuId: String? by lazy {
        intent.getStringExtra(EXTRA_MENU_ID)?.takeIf { it.isNotBlank() }
    }

    override val pageTitle: String
        get() = if (editingMenuId == null) "新建菜单" else "修改菜单"

    /** 顶部就是菜单名输入框 + 步骤条 + 选菜区，只能用紧凑标题栏。 */
    override val compactBar = true

    override val editing: MenuDef?
        get() = editingMenuId?.let { State.data?.menus?.firstOrNull { menu -> menu.id == it } }

    /**
     * 进入这一页时把库里的菜品装进"已选"。
     *
     * 必须在这里做（而不是在 composable 里）：`State.selected` 是全局的，
     * 如果放在组合里赋值，每次重组都会把用户刚勾掉的那道菜**重新勾回来**。
     */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        State.selected.clear()
        editing?.foods?.let { State.selected.addAll(it) }
        // 列菜单不是用餐流程：把"用餐中加菜"与选菜筛选都归零，
        // 并标记这是菜单编辑（选菜列表据此不画步骤条）
        State.addingFood = false
        State.editingMenu = true
        State.query = ""
        State.category = "全部"
    }

    override fun onDestroy() {
        // 离开这一页一定要清掉标记，否则"自定义本餐菜单"里的步骤条会消失
        State.editingMenu = false
        super.onDestroy()
    }

    @Composable
    override fun Page() {
        MenuEditorScreen(this)
    }

    override fun onResume() {
        super.onResume()
        // 与加菜页一致：进"选菜"页面就对齐一次服务器菜品库
        AppCore.syncDishesFromServer("菜单编辑")
    }

    @Composable
    override fun BottomBar() {
        // 底部动作在 MenuEditorScreen 里画（它需要拿到菜单名输入框的值），这里留空
    }

    /* ------------------------------------------------------- MenuEditorHost */

    override fun saveMenu(menuId: String?, name: String, dishIds: List<String>) {
        val message = AppCore.saveMenu(menuId, name, dishIds)
        toast(message)
        // 保存成功才关闭：失败（没填名字 / 没选菜）时留在页面上让用户改
        if (message.startsWith("已")) finish()
    }

    override fun deleteMenu(menuId: String) {
        toast(AppCore.deleteMenu(menuId))
        finish()
    }

    /* ------------------------------------------- 从菜单管理页跳进来的便捷方法 */
    companion object {
        fun intentFor(context: android.content.Context, menuId: String?): Intent =
            Intent(context, MenuEditorActivity::class.java).apply {
                if (menuId != null) putExtra(EXTRA_MENU_ID, menuId)
            }
    }
}
