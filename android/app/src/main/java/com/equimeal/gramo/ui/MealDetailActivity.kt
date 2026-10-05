package com.equimeal.gramo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.equimeal.gramo.AppCore
import com.equimeal.gramo.MealEditor
import com.equimeal.gramo.MealRecord
import com.equimeal.gramo.State

/*
 * 用餐记录详情页（点记录列表里的一行进来）。
 *
 * 入口在「用餐记录」列表：点一行 → 带上这一条的 id 打开本页，本页按 id 现查一次数据库，
 * 把记录装进 [State.mealEditor]（含每一口明细）。用全局可变状态而不是 Intent 传对象，
 * 是因为这一页要**就地修改**多条数据（名称、时长、口数、重量、热量），
 * 一个可变状态对象最直接；退出时清掉即可。
 *
 * 它和「用餐结果」是**同一套界面**（复用 `ResultValueRow` / `mealDerivedRows`），
 * 但语义不同：
 * - 结果页：刚吃完，先展示结果，点「完成」收尾这一餐；
 * - 详情页：翻历史记录，**改一次就存一次**（本地立即生效，登录时同步云端），没有"完成"这一步。
 */

/** 要打开哪条记录。 */
const val EXTRA_MEAL_ID = "meal_id"

class MealDetailActivity : BasePageActivity() {

    override val pageTitle: String
        get() = State.mealEditor?.name?.ifBlank { "用餐记录" } ?: "用餐记录"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_MEAL_ID, 0L)
        if (id <= 0L || State.mealEditor?.id != id) {
            State.mealEditor = if (id > 0L) AppCore.loadMealEditor(id) else null
        }
        if (State.mealEditor == null) {
            AppCore.toast("这条记录已经不在了")
            finish()
        }
    }

    override fun onDestroy() {
        // 离开就清掉，免得上一次编辑的对象留在内存里被别的页面误用
        if (isFinishing) State.mealEditor = null
        super.onDestroy()
    }

    @Composable
    override fun Page() {
        val editor = State.mealEditor ?: return
        MealDetailScreen(host = DetailHost(editor), editor = editor)
    }

    /**
     * 详情页的宿主实现。
     *
     * 做成一个内部类而不是让 Activity 直接实现 [MealDetailHost]：那样会与
     * [BasePageActivity] 的成员混在一个命名空间里（这个接口里的名字很通用，比如 `toast`），
     * 单独一个小类更清楚，也顺手把"当前编辑哪一条"钉住。
     */
    private inner class DetailHost(private val editor: MealEditor) : MealDetailHost {

        override fun toast(message: String) = AppCore.toast(message)

        override fun saveMealName(name: String) {
            toast(AppCore.saveMealEditor(editor, name = name))
        }

        override fun saveMealField(field: String, value: String) {
            toast(AppCore.saveMealEditor(editor, field = field, value = value))
        }

        override fun deleteThisMeal() {
            toast(AppCore.deleteMealEditor(editor))
            finish()
        }
    }

    companion object {
        fun intentFor(context: Context, record: MealRecord): Intent =
            Intent(context, MealDetailActivity::class.java).putExtra(EXTRA_MEAL_ID, record.id)
    }
}
