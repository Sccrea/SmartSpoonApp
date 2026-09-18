package com.smartspoon.l2

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.smartspoon.l2.ui.SettingsActions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用级单例 —— 阶段 3 第 6 步：把那三个**进程级挂钩**从 Activity 上摘下来。
 *
 * 阶段 2 里 `MainActivity.onCreate` 会把自己挂到 `MealSession.toastSink /
 * storeProvider / reloadSink` 上。用餐流程拆成 4 个独立 Activity 之后这个做法就塌了：
 *
 * - 「用餐中」启动时会把三个挂钩覆盖成自己的 → 没问题；
 * - 但它 `finish()` 之后 `MainActivity` **不会重新执行 `onCreate`**（它只是在后台活着）
 *   → 挂钩仍然指向**已经销毁的那个 Activity**；
 * - 结果 `MealSession.say(...)` 会往一个死掉的 Activity 弹提示，而「重读本地库」
 *   会去碰一个已经走完 `onDestroy` 的宿主。
 *
 * 正确的修法不是在 `onResume` 里来回重挂（那样谁在前台仍然要赌），而是让挂钩**根本不依赖
 * Activity**：全部在 `Application.onCreate` 里一次挂好，并且只使用 `applicationContext`。
 * 于是「记录 / 落库 / 提示」这三件事与任何 Activity 的生死都无关了。
 *
 * 顺带解决第二件事：原来 `MainActivity` 与 `SettingsActions` 各自 `Store(context)` 建了一份
 * 连接。现在全应用只有 [store] 这一份。
 */
class SmartSpoonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCore.attach(this)
    }
}

object AppCore {

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())

    /** 全应用**唯一**的一份本地库连接。 */
    lateinit var store: Store
        private set

    /** 由 `Application.onCreate` 调用，早于任何 Activity。 */
    fun attach(application: Application) {
        app = application
        store = Store(application)
        MealSession.storeProvider = { store }
        MealSession.toastSink = { message -> toast(message) }
        MealSession.reloadSink = { loadFromStore() }
    }

    /** 任何线程都能调的提示出口（内部走 `applicationContext`，不会打到已销毁的窗口上）。 */
    fun toast(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }

    /* ------------------------------------------------------------ 界面数据 */

    /**
     * 从本地数据库读取全部界面数据（离线可用）。
     *
     * 原先长在 `MainActivity` 里，因此「用餐结果 → 完成」重读本地库时必须先找到一个还活着的
     * `MainActivity`；现在它只依赖 [store] 与全局 [State]，任何 Activity（或没有任何 Activity）
     * 都可以调。读取内容与旧版逐字一致。
     */
    fun loadFromStore() {
        val dishes = store.dishes()
        State.data = Bootstrap(
            foods = dishes,
            menus = store.menus(),
            categories = listOf("全部", "肉类", "蔬菜", "水果"),
            folders = emptyList(),
            favoriteTimes = store.favorites(),
            records = store.meals(),
            stats = store.stats(),
            chart = store.chartData(),
            devices = State.data?.devices ?: demoDevices(),
            user = State.data?.user ?: User("张三"),
            preselect = State.selected.ifEmpty { dishes.take(4).map { it.id } },
            result = State.result,
            dishesUpdated = "",
        )
        State.online = true
        State.loadError = null
    }

    /** 第一次加载时的演示勺子（服务器还没连上也要能看到「连接智味勺」里的设备）。 */
    private fun demoDevices(): MutableList<Device> = mutableListOf(
        Device("s1", "张三的智味勺", 60, true, true, true),
        Device("s2", "李四的智味勺", 46, true, true, true),
        Device("s3", "王五的智味勺", 60, false, true, true),
        Device("s4", "Sccrea的智味勺", 60, false, true, false),
        Device("s5", "张三妈妈的智味勺", 46, true, false, false),
    )

    /* -------------------------------------------------------------- 服务器 */

    private fun candidates(): List<String> {
        val list = mutableListOf(State.server)
        State.FALLBACK_SERVERS.forEach { if (!list.contains(it)) list.add(it) }
        return list
    }

    /**
     * 自动探测可用的数据服务器（勺子模拟数据在这里）：
     * 依次尝试已保存地址、10.0.2.2、本项目的开发机地址，第一个 /api/health 通的就用它。
     */
    fun detectServer(onDone: () -> Unit = {}) {
        Thread {
            val hit = firstAlive(candidates())
            main.post {
                if (hit != null) {
                    if (State.server != hit) {
                        State.server = hit
                        app.getSharedPreferences(SettingsActions.PREFS, Context.MODE_PRIVATE).edit()
                            .putString("server_url", hit).apply()
                    }
                    State.online = true
                } else {
                    State.online = false
                }
                onDone()
            }
        }.start()
    }

    /**
     * 从服务器导入菜品库：先找到活着的地址，再拉 bootstrap 落进本地库。
     * 结果以一句提示文字交回调用方。
     */
    fun importFromServer(onDone: (String) -> Unit) {
        Thread {
            val hit = firstAlive(candidates())
            if (hit == null) {
                main.post {
                    State.online = false
                    State.loadError = "无法连接服务器，请在「设置 → 杂项」里填写服务器地址"
                    onDone("没有从服务器读到菜品")
                }
                return@Thread
            }
            State.server = hit
            val data = Api.fetchBootstrap(hit)
            main.post {
                if (data == null) {
                    State.online = false
                    State.loadError = "读取菜品信息失败"
                    onDone("没有从服务器读到菜品")
                } else {
                    val added = store.importFoods(data.foods)
                    State.online = true
                    State.loadError = null
                    loadFromStore()
                    onDone(if (added > 0) "已从服务器导入 $added 道菜" else "菜品库已是最新")
                }
            }
        }.start()
    }

    private fun firstAlive(candidates: List<String>): String? {
        val hit = AtomicReference<String>()
        val latch = CountDownLatch(candidates.size)
        candidates.forEach { base ->
            Thread {
                try {
                    if (hit.get() == null && Api.probe(base)) hit.compareAndSet(null, base)
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await(2500, TimeUnit.MILLISECONDS)
        return hit.get()
    }

    /* -------------------------------------------------------------- 小工具 */

    /** 只翻转选中状态（加菜页 / 托盘共用）。 */
    fun toggleFood(id: String): Boolean =
        if (State.selected.contains(id)) {
            State.selected.remove(id)
            false
        } else {
            State.selected.add(id)
            true
        }

    /* -------------------------------------------------------- 菜品增删改查 */

    /** 菜品行的 ⋮ 菜单 → 收藏 / 取消收藏。返回要提示的文字。 */
    fun toggleFavorite(food: Food): String {
        val favorited = State.data?.favoriteTimes?.containsKey(food.id) == true
        store.toggleFavorite(food.id, !favorited)
        loadFromStore()
        return if (favorited) "已取消收藏" else "已收藏"
    }

    /**
     * 菜品编辑弹窗的「保存」。返回要提示的文字。
     * 名称为空时**不关弹窗**（与旧版一致，让用户接着改）。
     */
    fun saveDish(foodId: String?, name: String, category: String, density: Double): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "请填写菜品名称"
        if (foodId == null) {
            store.addDish(trimmed, category, density, "🍽")
        } else {
            store.updateDish(foodId, trimmed, category, density)
        }
        closeOverlay()
        loadFromStore()
        return "已保存"
    }

    /** 删除菜品确认框的「删除」。返回要提示的文字。 */
    fun deleteDish(foodId: String): String {
        closeOverlay()
        store.deleteDish(foodId)
        State.selected.remove(foodId)
        loadFromStore()
        return "已删除"
    }

    /** 用餐记录详情弹窗的「删除记录」。返回要提示的文字。 */
    fun deleteMeal(mealId: Long): String {
        closeOverlay()
        store.deleteMeal(mealId)
        loadFromStore()
        return "已删除这条记录"
    }

    /* -------------------------------------------------------------- 弹窗 */

    fun closeOverlay() {
        State.dialog = null
    }

    fun showConnectDialog(picking: Boolean) {
        State.dialog = AppDialog.Connect(picking)
    }

    fun selectConnectedDevice(deviceId: String) {
        State.connectedId = deviceId
        showConnectDialog(true)
    }

    fun zeroSpoonWeight() {
        State.meal.spoonWeight = 0
        toast("已归零")
    }

    fun showDishEditor(food: Food?) {
        State.dialog = AppDialog.DishEditor(food?.id)
    }

    fun confirmDeleteDish(food: Food) {
        State.dialog = AppDialog.DeleteDish(food.id)
    }

    fun openRecognizeDialog() {
        State.dialog = AppDialog.Recognize
    }

    fun showMealDetail(record: MealRecord) {
        State.dialog = AppDialog.MealDetail(record)
    }

    fun editResultValue(field: String) {
        State.dialog = AppDialog.EditResult(field)
    }

    fun showBiteDetail() {
        State.dialog = AppDialog.BiteDetail
    }

    /**
     * 连接智味勺弹窗的「开始用餐」：重置本餐数据并弹出用餐提醒。
     * 背景页面保持不变（从哪一页点的「开始用餐」就停在哪一页）。
     */
    fun startMeal() {
        closeOverlay()
        val meal = State.meal
        meal.minutes = 0
        meal.bites = 0
        meal.totalWeight = 0
        meal.totalEnergy = 0.0
        meal.spoonWeight = 0
        meal.spoonEnergy = 0.0
        meal.paused = false
        meal.food = State.selected.firstOrNull()?.let { State.food(it)?.name } ?: "小米粥"
        MealSession.currentBites.clear()
        MealSession.currentMealId = 0L
        MealSession.currentMealStart = System.currentTimeMillis()
        State.dialog = AppDialog.Remind
    }

    /** 选择本餐菜单：点一份菜单 = 用它当本餐内容，然后弹「连接智味勺」。 */
    fun pickMenu(menu: MenuDef) {
        State.selected.clear()
        State.selected.addAll(menu.foods)
        MealSession.currentMealName = menu.name
        showConnectDialog(picking = false)
    }

    /**
     * 修正数据弹窗的「保存」。
     *
     * 输入框里填的是**当前单位下的数值**（kg / 两、kcal…），这里换算回基准单位
     * （分钟 / 口 / g / kJ）再存 —— 所以改完单位，结果页那几行会跟着换，数值不会错。
     * 返回要提示的文字。
     */
    fun saveResultField(field: String, value: String): String {
        val number = value.trim().toDoubleOrNull()
            ?: return "请输入数字"
        when (field) {
            "duration" -> State.result.minutes = number.toInt()
            "bites" -> State.result.bites = number.toInt()
            "weight" -> State.result.weightGrams = Units.toGrams(number)
            else -> State.result.energyKj = Units.toKj(number)
        }
        closeOverlay()
        return "已更新"
    }

    /** 识别结果弹窗的「加入菜单」。返回要提示的文字。 */
    fun addRecognizedToMenu(foodId: String): String {
        if (!State.selected.contains(foodId)) State.selected.add(foodId)
        closeOverlay()
        return "已加入本餐菜单"
    }

    /** 把识别出来的菜品保存到服务器（POST /api/dishes）。 */
    fun saveDishToServer(name: String, calorie: Double, onDone: (String) -> Unit) {
        Thread {
            val saved = Api.saveDish(
                State.server, name, "其他",
                if (calorie > 0) Math.round(calorie / 100.0 * 100) / 100.0 else 1.0, 1
            )
            main.post {
                if (saved == null) {
                    onDone("保存失败：服务器不可用")
                } else {
                    State.data?.foods?.add(saved)
                    State.selected.add(saved.id)
                    closeOverlay()
                    onDone("已保存到服务器菜品库并加入本餐")
                }
            }
        }.start()
    }
}
