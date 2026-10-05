package com.equimeal.gramo

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.equimeal.gramo.ui.SettingsActions
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

    /** 应用级 Context（只读，供 [BleSpoon] / 持久化这类不需要界面的地方用）。 */
    val appContext: Context? get() = if (::app.isInitialized) app else null

    /**
     * 当前生效的本地库。登录/退出登录时会**换掉它**（见 [switchAccount]）。
     *
     * 刻意不用"在属性初始化式里 `Store(application)`"：`app` 还没挂上（`attach` 是后面才调的），
     * 那样写编译就过不去 —— 这里由 [attach] 第一次赋值，之后只在换账号时替换。
     */
    lateinit var store: Store
        private set

    /** 由 `Application.onCreate` 调用，早于任何 Activity。 */
    fun attach(application: Application) {
        app = application
        // 未登录先用默认库；如果上次登录过，MainActivity 启动时会切到那个账号的库
        store = Store(application, Store.forAccount(State.account?.username))
        MealSession.storeProvider = { store }
        MealSession.toastSink = { message -> toast(message) }
        MealSession.reloadSink = { loadFromStore() }
        // 一餐落库之后就去和云端对一下（未登录时 syncCloudData 直接返回）
        MealSession.cloudSyncSink = { syncCloudData("用餐结束") }
        // 蓝牙：连接状态必须与 Activity 生死无关（详见 BleSpoon / SpoonLink 的类注释）
        BleSpoon.attach(application)
    }

    /**
     * 切换账号：换掉本地库并重读。
     *
     * 这是"一个账号一个库文件"的落地点 —— 换账号之后，
     * 菜品、菜单、用餐记录、收藏全部换成那个账号自己的，互不串数据。
     */
    fun switchAccount(username: String?) {
        val ctx = appContext ?: return
        val target = Store.forAccount(username)
        if (store.dbName == target) return
        try {
            store.close()
        } catch (e: Exception) {
            Log.w("AppCore", "关闭旧库失败", e)
        }
        store = Store(ctx, target)
        MealSession.storeProvider = { store }
        // 换库之后"当前这一餐"的上下文已经不成立了，清掉避免把 A 的口写进 B 的记录
        MealSession.currentBites.clear()
        MealSession.currentMealId = 0L
        loadFromStore()
    }

    /** 任何线程都能调的提示出口（内部走 `applicationContext`，不会打到已销毁的窗口上）。 */
    fun toast(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }

    /* ------------------------------------------------------------ 界面数据 */

    /**
     * 从本地数据库读取全部界面数据（**离线可用，与服务器无关**）。
     *
     * 原先长在 `MainActivity` 里，因此「用餐结果 → 完成」重读本地库时必须先找到一个还活着的
     * `MainActivity`；现在它只依赖 [store] 与全局 [State]，任何 Activity（或没有任何 Activity）
     * 都可以调。
     *
     * ## 数据来源只有两处
     *
     * | 字段 | 来源 |
     * | --- | --- |
     * | 菜品库 / 菜单 / 收藏 | 本地 SQLite（`dishes` / `menus` / `menu_items`） |
     * | 用餐记录 / 每一口 / 统计 / 折线图 | 本地 SQLite（`meals` / `bites`）**现算** |
     * | 用户 | 当前登录账号（未登录是空名字） |
     * | 设备 | 真机蓝牙扫描 + 本机记住的那台（见 [rememberedDevices]） |
     *
     * 以前这里会掺一批服务器示例数据（示例记录、写死的统计与图表、5 台假勺子、写死的"张三"），
     * 那都是"模板"；现在全部去掉 —— 界面上出现的每一条都是用户自己的。
     */
    fun loadFromStore() {
        val dishes = store.dishes()
        val account = State.account
        /*
         * 分类胶囊**由菜品库现算**，不写死。
         *
         * 原来这里是 `listOf("全部", "肉类", "蔬菜", "水果")` 四个固定项，而用户自己加的菜
         * 分类常常是"其他"（拍照识别保存的菜、手动新增时没选分类）—— 结果菜品库里有菜、
         * 界面上却没有能选中它的胶囊；再加上「分类」这个筛选是**跨页面保留**的
         * （`State.category` 是全局状态），于是从收藏页带着"水果"进选菜页就会看到
         * 「没有匹配的食物」，而实际上库里有菜。现在分类跟着库走，并且在选菜页会重置筛选。
         */
        val categories = buildList {
            add("全部")
            dishes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted().forEach { add(it) }
            // "其他"永远留在最后：它是"没归类"的兜底，不是主要内容
            if (remove("其他")) add("其他")
        }
        // 之前选中的分类如果在新的库里不存在了（比如换账号、或那道菜被删了），回落"全部"
        if (State.category !in categories) State.category = "全部"

        State.data = Bootstrap(
            foods = dishes,
            menus = store.menus(),
            categories = categories,
            folders = emptyList(),
            favoriteTimes = store.favorites(),
            records = store.meals(),
            stats = store.stats(),
            // 传当前设置进去：x/y 轴、显示范围、单位都在这里生效（见 Store.chartData 的说明）
            chart = store.chartData(
                xAxis = State.axisX,
                yAxis = State.axisY,
                count = when (State.chartRange) {
                    "近 7 次" -> 7
                    "近 5 次" -> 5
                    else -> null
                },
            ),
            devices = rememberedDevices(State.data?.devices ?: mutableListOf()),
            // 登录后就用账号昵称；未登录给空名字，界面显示"未登录"而不是编一个"张三"出来
            user = State.data?.user?.takeIf { it.name.isNotBlank() }
                ?: User(account?.displayName ?: ""),
            preselect = State.selected.ifEmpty { dishes.take(4).map { it.id } },
            result = State.result,
            dishesUpdated = "",
        )
        State.online = true
        State.loadError = null
        /*
         * 重读本地库会重建 Bootstrap（devices 这一份列表跟着换），所以顺手把**当前连着的
         * 蓝牙勺子**补回去 —— 否则「用餐结果 → 完成」这类会调 loadFromStore 的动作之后，
         * "当前已连接"会突然变成"无"（真机明明还连着），看起来像掉线了。
         */
        if (SpoonLink.ready) BleSpoon.activeDevice()
    }

    /**
     * 设备列表**只有真实存在的勺子**。
     *
     * 这里刻意不再有任何"演示勺子"（原来有 5 台：张三的智味勺、李四的智味勺、王五的智味勺、
     * Sccrea的智味勺、张三妈妈的智味勺）。它们是模板数据，会让人分不清"哪些是我真连过的勺子"——
     * 而设备管理页的意义恰恰是回答这个问题。
     *
     * 所以列表只来自两处：
     * - **真机蓝牙**：扫描发现的（`BleSpoon.deviceRowFor`）与当前连着的（`BleSpoon.activeDevice`）；
     * - **本机记住的**：上次连过 / 保存过的那一台（就是下面这段补回逻辑）。
     *
     * 一台都没有时界面显示空状态并引导去扫描 —— 那才是真实情况。
     */
    private fun rememberedDevices(current: MutableList<Device>): MutableList<Device> {
        val address = State.bleSpoonAddress
        if (address.isNotBlank() && current.none { it.id == address }) {
            current.add(
                Device(
                    id = address,
                    name = State.bleNames[address] ?: State.bleSpoonName.ifBlank { "智味勺" },
                    // 未知电量用 -1（界面不显示），别再写死 100%
                    battery = -1,
                    saved = State.savedBle.contains(address),
                    nearby = true,
                    picker = true,
                    protocol = Device.PROTOCOL_BLE,
                )
            )
        }
        return current
    }

    /* -------------------------------------------------------------- 账号 */

    /**
     * 注册 / 登录的公共实现。
     *
     * 三步：
     * 1. 请求服务器拿账号与令牌（**密码只在这一步用一次，绝不落盘**）；
     * 2. 记住登录态（令牌 + 昵称 → 偏好）；
     * 3. **切换到该账号的本地库** —— 换账号 = 换库文件，记录天然隔离。
     *
     * 返回给界面显示的一句话；成功时返回 null（界面自己跳转）。
     */
    fun authenticate(
        context: Context,
        username: String,
        password: String,
        name: String,
        register: Boolean,
        onDone: (String?) -> Unit,
    ) {
        if (State.accountBusy) return
        State.accountBusy = true
        State.accountMessage = null
        Thread {
            val result = if (register) {
                Api.register(State.server, username, password, name)
            } else {
                Api.login(State.server, username, password)
            }
            main.post {
                State.accountBusy = false
                when (result) {
                    is AuthResult.Ok -> {
                        State.rememberAccount(context, result.user)
                        switchAccount(result.user.username)
                        State.accountMessage = null
                        // 菜品库在服务器上、所有账号共用：登录后立刻对齐一次，
                        // 用户马上就能看到服务器上的最新菜品
                        syncDishesFromServer("登录后")
                        // 用餐记录与食用次数是**按账号存在云端**的：登录时把云端那份覆盖到本地
                        syncCloudData("登录后")
                        onDone(null)
                    }
                    is AuthResult.Fail -> {
                        State.accountMessage = result.message
                        onDone(result.message)
                    }
                }
            }
        }.start()
    }

    /**
     * 退出登录。
     *
     * 会通知服务器把这个令牌作废（失败也无所谓，本地先退干净），
     * 然后切回默认库 —— **不删除**原来那个账号的库文件，下次登录还能看到自己的数据。
     */
    fun logout(context: Context) {
        val current = State.account
        State.forgetAccount(context)
        switchAccount(null)
        if (current != null) {
            Thread {
                // 先把本地这份推上去（刚吃完就退出不该丢记录），再作废令牌
                flushCloudBeforeLogout(current.token)
                Api.logout(State.server, current.token)
            }.start()
        }
    }

    /**
     * 启动时校验登录态。
     *
     * 只有服务器**明确说"令牌无效"**（401）才会退出登录；
     * 网络不通、服务器没升级，都当作"先信本地"（否则一断网就把人踢出去）。
     */
    fun verifySession() {
        val current = State.account ?: return
        Thread {
            val valid = Api.checkSession(State.server, current.token) ?: return@Thread
            if (!valid) {
                main.post {
                    // 令牌过期/被顶掉：退出登录但保留数据
                    appContext?.let { ctx -> State.forgetAccount(ctx) }
                    switchAccount(null)
                    toast("登录已过期，请重新登录")
                }
            }
        }.start()
    }

    /** 改昵称：先改服务器，成功后更新本地登录态。返回要提示的一句话。 */
    fun renameAccount(context: Context, name: String, onDone: (String) -> Unit) {
        val current = State.account
        if (current == null) {
            onDone("请先登录")
            return
        }
        Thread {
            val result = Api.rename(State.server, current.token, name)
            main.post {
                when (result) {
                    is AuthResult.Ok -> {
                        State.rememberAccount(context, result.user)
                        onDone("昵称已改为「${result.user.displayName}」")
                    }
                    is AuthResult.Fail -> onDone(result.message)
                }
            }
        }.start()
    }

    /* ------------------------------------------- 云端同步（用餐记录 / 食用次数） */

    /** 最近一次"与云端对齐"的时间（0 = 本次进程还没成功过）。 */
    @Volatile
    var cloudSyncedAt = 0L
        private set

    @Volatile
    private var syncingCloud = false

    /**
     * 与**当前登录账号**的云端对齐用餐记录与食用次数。
     *
     * ## 归属规则（这是需求定下来的）
     *
     * | 数据 | 存在哪 |
     * | --- | --- |
     * | 菜品库 | 服务器，**所有账号共用一份**（本地是缓存） |
     * | **用餐记录、食用次数** | **云端按账号存**；未登录只存本地 |
     * | 收藏、菜单、勺子的名字 | 本地（属于"这台设备怎么用"） |
     *
     * ## 方向：先上报，再以云端为准覆盖本地
     *
     * 1. 把本地这份**上报**（服务器按"结束时刻"合并，两台设备各自的记录都不会丢）；
     * 2. 把服务器返回的合并结果**整体覆盖**本地 —— 这就是"登录时自动覆盖云端数据到本地"；
     * 3. 食用次数先取云端那份：本地有更新的（未登录期间吃出来的）就上报覆盖，
     *    否则用云端的覆盖本地。
     *
     * **任何一步失败都不动本地**：网络不通、服务器还是旧版没有这些接口时，
     * 宁可继续用本地那份，也不能把用户攒的记录清掉。
     */
    fun syncCloudData(reason: String = "") {
        val account = State.account ?: return          // 未登录：数据只留本地
        if (syncingCloud) return
        syncingCloud = true
        val token = account.token
        Thread {
            try {
                val base = State.server
                val localRecords = store.cloudRecords()

                // 1 + 2) 记录：上报本地，拿回服务器合并后的完整列表并覆盖本地
                val merged = Api.uploadMeals(base, token, localRecords)
                if (merged != null) {
                    store.replaceRecords(merged)
                    android.util.Log.i(
                        "AppCore",
                        "云端记录已对齐（$reason）：本地 ${localRecords.size} 条 → 云端 ${merged.size} 条",
                    )
                } else {
                    android.util.Log.w("AppCore", "云端记录对齐失败（$reason）：保留本地那份")
                }

                // 3) 食用次数：云端有就用云端的，本地更多就上报覆盖
                val localUsages = store.usages()
                val cloudUsages = Api.fetchUsages(base, token)
                if (cloudUsages != null) {
                    val localTotal = localUsages.values.sum()
                    val cloudTotal = cloudUsages.values.sum()
                    if (localTotal > cloudTotal) {
                        Api.uploadUsages(base, token, localUsages)
                    } else {
                        store.applyUsages(cloudUsages)
                    }
                }

                cloudSyncedAt = System.currentTimeMillis()
                State.online = true
                main.post { loadFromStore() }
            } catch (e: Exception) {
                android.util.Log.w("AppCore", "云端同步异常（$reason）：${e.message}")
            } finally {
                syncingCloud = false
            }
        }.start()
    }

    /** 退出登录前把本地这份推上去，避免"刚吃完就退出"丢掉记录。 */
    private fun flushCloudBeforeLogout(token: String) {
        try {
            Api.uploadMeals(State.server, token, store.cloudRecords())
            Api.uploadUsages(State.server, token, store.usages())
        } catch (e: Exception) {
            android.util.Log.w("AppCore", "退出前上报失败：${e.message}")
        }
    }

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
                    State.loadError = "无法连接服务器，请在「设置 → 杂项」点「服务器地址」填写"
                    onDone("没有从服务器读到菜品")
                }
                return@Thread
            }
            State.server = hit
            val data = Api.fetchBootstrapAuthed(hit, State.account?.token.orEmpty())
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

    /* ------------------------------------------------------- 菜品库自动同步 */

    /** 菜品库最近一次"从服务器刷新成功"的时间（0 = 本次进程还没成功过）。 */
    @Volatile
    var dishesSyncedAt = 0L
        private set

    /** 正在同步中（避免列菜单时反复发请求）。 */
    @Volatile
    private var syncingDishes = false

    /**
     * **菜品库是所有账号共用的一份**，存在服务器上（`data/dishes.json`）。
     *
     * 本地库里的菜品只是**离线缓存** —— 没网时照样能看菜单、能选菜，联网后自动对齐。
     * 因此这里不做"一个账号一份菜品"：同一台手机换个账号登录，看到的菜品库应当是一样的，
     * 否则"这道菜在服务器上存在"这件事在 App 里会变得时有时无。
     *
     * 调用时机：
     * - App 启动（[MainActivity]）；
     * - **每次列菜单**（快速开始 / 自定义本餐菜单 / 菜单管理）—— 这是用户最可能看到旧数据的地方；
     * - 登录成功之后（换了账号，界面要立刻反映服务器的真实现状）。
     *
     * 静默同步：失败不弹提示（离线是正常状态，本地缓存照常用），只有成功后才刷新界面。
     */
    fun syncDishesFromServer(reason: String = "") {
        if (syncingDishes) return
        syncingDishes = true
        Thread {
            try {
                val hit = firstAlive(candidates()) ?: run {
                    State.online = false
                    return@Thread
                }
                State.server = hit
                val data = Api.fetchBootstrapAuthed(hit, State.account?.token.orEmpty()) ?: run {
                    State.online = false
                    return@Thread
                }
                val before = store.dishes().size
                val added = store.importFoods(data.foods)
                val after = store.dishes().size
                dishesSyncedAt = System.currentTimeMillis()
                State.online = true
                State.loadError = null
                main.post {
                    // 只有真的变了才重读界面：否则每次进菜单都会重组一遍列表
                    if (after != before) {
                        loadFromStore()
                        if (added > 0) toast("已从服务器同步 $added 道新菜")
                    }
                }
                android.util.Log.i("AppCore", "菜品库已同步（$reason）：新增 $added 道，共 $after 道")
            } finally {
                syncingDishes = false
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

    /* -------------------------------------------------------------- 菜单 */

    /**
     * 保存菜单编辑页的结果：新建（[menuId] 为 null）或改写一份已有菜单。
     *
     * 返回要提示的文字。名称里外都 trim 过；菜品按选择顺序落库（顺序就是托盘里的顺序）。
     */
    fun saveMenu(menuId: String?, name: String, dishIds: List<String>): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "请填写菜单名称"
        if (dishIds.isEmpty()) return "请至少选择一道菜"

        if (menuId == null) {
            store.createMenu(trimmed, dishIds)
        } else {
            store.updateMenu(menuId, trimmed, dishIds)
        }
        loadFromStore()
        return if (menuId == null) "已创建菜单「$trimmed」" else "已保存菜单「$trimmed」"
    }

    /** 删除一份菜单。返回要提示的文字。 */
    fun deleteMenu(menuId: String): String {
        val name = store.menu(menuId)?.name
        store.deleteMenu(menuId)
        loadFromStore()
        return if (name.isNullOrBlank()) "已删除菜单" else "已删除菜单「$name」"
    }

    /* -------------------------------------------------------------- 弹窗 */

    fun closeOverlay() {
        State.dialog = null
    }

    /**
     * 打开「连接智味勺」弹窗。
     *
     * `picking = true`（列出附近的勺子）时顺手启动一次扫描。**权限没给也不要紧**：
     * 弹窗里那行状态会显示"需要蓝牙权限"，并给出「授予蓝牙权限」按钮
     * （见 `ConnectDialog`）—— 之所以不在这里直接申请，是因为这里的 Context 是应用级的，
     * 而 Android 的运行时权限必须由一个 Activity 发起（那条路走
     * [BlePermissionActivity] 这个透明中转页）。
     */
    fun showConnectDialog(picking: Boolean) {
        State.dialog = AppDialog.Connect(picking)
        if (!picking) return
        val ctx = appContext ?: return
        when {
            !SpoonLink.hasBluetooth() -> State.bleHint = "这台设备没有蓝牙"
            !State.hasBlePermissions(ctx) -> State.bleHint = "需要蓝牙权限才能扫描附近的智味勺"
            !SpoonLink.isBluetoothOn() -> State.bleHint = "蓝牙未开启，请先打开手机蓝牙"
            else -> SpoonLink.startScan()
        }
    }

    fun selectConnectedDevice(deviceId: String) {
        State.connectedId = deviceId
        showConnectDialog(true)
    }

    /**
     * 「点击归零重量」。
     *
     * **手机与勺子各做一次**，缺一不可：
     * - 手机侧立刻清零，界面马上有反馈；
     * - 同时把 `ZERO` 下发给勺子让它重新 `tare()` —— 只清手机侧的话，下一个
     *   状态帧（100ms 后）就把旧读数顶回来，看起来就是"点了归零却没反应"。
     */
    fun zeroSpoonWeight() {
        State.meal.spoonWeight = 0.0
        State.meal.spoonEnergy = 0.0
        if (SpoonLink.ready) {
            SpoonLink.zero()
            toast("已归零（已通知勺子重新去皮）")
        } else {
            toast("已归零")
        }
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

    /* ------------------------------------------------ 用餐记录详情页（可编辑） */

    /**
     * 把一条记录装进 [State.mealEditor]，供「用餐记录详情页」显示与修改。
     *
     * 时长由起止时刻算出来（库里没有"时长"这一列），口数/重量/热量直接读列。
     * 返回 null 表示这条记录已经不在了（比如在别处被删掉）。
     */
    fun loadMealEditor(mealId: Long): MealEditor? {
        val record = store.meals().firstOrNull { it.id == mealId } ?: return null
        val minutes = ((record.endedAt - record.startedAt) / 60_000L).toInt().coerceAtLeast(1)
        return MealEditor(
            id = record.id,
            name = record.menu,
            minutes = minutes,
            bites = record.bites,
            weightGrams = record.weight,
            energyKj = record.energy,
            startedAt = record.startedAt,
            endedAt = record.endedAt,
            biteList = store.bites(record.id),
        )
    }

    /**
     * 保存详情页的一次修改。
     *
     * **传什么存什么**：`name` 与 `field/value` 二选一（改名走前者，改数值走后者）。
     * 数值的输入是**当前单位下的数字**（kg / 两 / kcal…），所以这里要按 [Units] 换算回基准单位
     * —— 与结果页的「修正数据」是同一套约定，改完单位再打开，预填的数字也跟着换。
     *
     * 存完立刻把**这一条**同步给云端（登录状态下），所以退出重进、换设备都能看到修改。
     * 返回要提示的文字。
     */
    fun saveMealEditor(editor: MealEditor?, name: String? = null, field: String? = null, value: String? = null): String {
        if (editor == null || editor.id <= 0L) return "记录已失效"
        var message = "已保存"

        if (name != null) {
            val clean = name.trim().ifBlank { editor.name.ifBlank { "本餐" } }
            editor.name = clean
        }
        if (field != null) {
            val number = value?.trim()?.toDoubleOrNull() ?: return "请输入数字"
            when (field) {
                "duration" -> editor.minutes = number.toInt().coerceAtLeast(0)
                "bites" -> editor.bites = number.toInt().coerceAtLeast(0)
                "weight" -> editor.weightGrams = Units.toGrams(number)
                "energy" -> editor.energyKj = Units.toKj(number)
                else -> return "无法修改这一项"
            }
            message = "已更新"
        }

        store.updateRecordFields(
            mealId = editor.id,
            name = editor.name,
            bites = editor.bites,
            weight = editor.weightGrams,
            energy = editor.energyKj,
            minutes = editor.minutes,
        )
        // 改了记录也要跟着改列表标题（详情页标题用的是它），并让记录列表立刻反映新数值
        loadFromStore()
        syncCloudData("修改记录")
        return message
    }

    /** 详情页的「删除记录」。返回要提示的文字。 */
    fun deleteMealEditor(editor: MealEditor?): String {
        if (editor == null || editor.id <= 0L) return "记录已失效"
        val name = editor.name
        store.deleteMeal(editor.id)
        State.mealEditor = null
        loadFromStore()
        // 云端是按 key（结束时刻）合并的，删本地不会把云端删掉 —— 这里说明一下，
        // 免得用户以为"删了就该到处都没有"
        syncCloudData("删除记录")
        return "已删除「$name」"
    }

    fun editResultValue(field: String) {
        State.dialog = AppDialog.EditResult(field)
    }

    fun showBiteDetail() {
        State.dialog = AppDialog.BiteDetail
    }

    /* -------------------------------------------------------- 设备名字 */

    /** 点设备行 → 改名字。 */
    fun showRenameDevice(deviceId: String) {
        State.dialog = AppDialog.RenameDevice(deviceId)
    }

    /**
     * 保存新的勺子名字。返回要提示的文字。
     *
     * 真机（BLE）会落到偏好里（见 [State.renameSpoon]），服务器模拟设备只改内存里这一行 ——
     * 后者的名字本来就来自服务器，改它只是让界面能区分开。
     */
    fun renameDevice(deviceId: String, name: String): String {
        val device = State.data?.devices?.firstOrNull { it.id == deviceId }
            ?: return "设备不存在"
        val clean = name.trim()
        if (clean.isEmpty()) return "名字不能为空"
        if (clean == device.name) {
            closeOverlay()
            return "名字没变"
        }
        if (device.isBle) {
            val ctx = appContext
            if (ctx != null) State.renameSpoon(ctx, deviceId, clean)
        }
        device.name = clean
        closeOverlay()
        return "已改名为「$clean」"
    }

    /**
     * 连接智味勺弹窗的「开始用餐」：重置本餐数据。
     *
     * 之后弹不弹「用餐提醒」由 [State.showMealRemind] 决定：
     * - 要弹（默认）→ 进 [AppDialog.Remind]，用户点「我已知晓」再进「用餐中」；
     * - 不弹 → 直接把弹窗清掉，界面那一层会立刻跳进「用餐中」
     *   （宿主在自己的 `startMeal()` 里检查 `State.dialog == null` 就知道该不该跳）。
     *
     * 用户点过一次「我已知晓」之后 [State.showMealRemind] 会被置 false（记在偏好里，
     * 重启也有效）；「设置 → 设备管理 → 用餐提醒」可以把它重新打开。
     */
    /**
     * 开始用餐。
     *
     * **前提：必须连着真勺子**（[SpoonLink.ready]）。没连上就把用户送去连接列表，而不是
     * 让他开始一餐"记不到任何数据"的用餐 —— 没有勺子的"用餐中"页面只会一直显示 0 g，
     * 最后落库一条空记录，对用户没有任何价值。
     *
     * [AppCore.startMeal] 会按「用餐提醒」开关决定要不要挂提醒弹窗：
     * - 挂上了（`dialog != null`）→ 停在这一页等用户点「我已知晓」；
     * - 没挂（用户已经关掉提醒）→ **直接进「用餐中」**，不再多按一次。
     */
    fun startMeal() {
        if (!SpoonLink.ready) {
            toast("请先连接智味勺")
            showConnectDialog(picking = true)
            return
        }
        closeOverlay()
        val meal = State.meal
        meal.minutes = 0
        meal.bites = 0
        meal.totalWeight = 0
        meal.totalEnergy = 0.0
        meal.spoonWeight = 0.0
        meal.spoonEnergy = 0.0
        meal.paused = false
        meal.food = State.selected.firstOrNull()?.let { State.food(it)?.name } ?: "小米粥"
        MealSession.currentBites.clear()
        MealSession.currentMealId = 0L
        MealSession.currentMealStart = System.currentTimeMillis()
        // 要弹提醒就挂上弹窗；不要弹就保持 dialog == null（宿主据此直接跳转）
        if (State.showMealRemind) State.dialog = AppDialog.Remind
    }

    /**
     * 选择本餐菜单：点一份菜单 = 用它当本餐内容。
     *
     * 与"自定义本餐菜单 → 选好了"同一套规则：**连着勺子才进确认页，没连就直接进连接列表** ——
     * 否则用户会停在"当前已连接：未连接"的页面上，而那里除了"重新选择"无事可做。
     */
    fun pickMenu(menu: MenuDef) {
        State.selected.clear()
        State.selected.addAll(menu.foods)
        MealSession.currentMealName = menu.name
        showConnectDialog(picking = !SpoonLink.ready)
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

    /**
     * 把识别出来的菜品保存到服务器（POST /api/dishes），**同时落本地库并立刻刷新界面**。
     *
     * ## 为什么必须落本地库
     *
     * 原来的做法是：拿到服务器返回的 `food` 之后直接 `State.data.foods.add(saved)`，
     * 用的是**服务器的 id**（形如 `"f16"`）。但本地库 `dishes.id` 是 `INTEGER PRIMARY KEY`（自动编号），
     * 于是有两个后果：
     * 1. 界面上当下能看到这道菜（列表按名字渲染），但**下一次重读本地库它就没了** ——
     *    库里根本没有这一行，表现就是"识别加进来的菜过一会儿自己消失了"；
     * 2. 更麻烦的是 `State.selected` 里存的是 `"f16"`：选它进本餐后要写进
     *    `menu_items.dish_id`（INTEGER），`"f16"` 会被存成 **0**，整份菜单都指向一道不存在的菜。
     *
     * 现在统一走"服务器为准 + 本地缓存"：先 POST 到服务器（所有账号共用同一份菜品库），
     * 再写进本地库拿到**真正的本地 id**，最后 `loadFromStore()` 重读 —— 菜品列表立刻显示新菜，
     * 加载/勾选用的也是能对上的 id。
     */
    fun saveDishToServer(name: String, calorie: Double, onDone: (String) -> Unit) {
        Thread {
            val density = if (calorie > 0) {
                Math.round(calorie / 100.0 * 100) / 100.0  // 百度给的是 kJ/100g，本地基准是 kJ/g
            } else {
                1.0
            }
            val saved = Api.saveDish(State.server, name, "其他", density, 1)
            main.post {
                if (saved == null) {
                    onDone("保存失败：服务器不可用")
                } else {
                    // 服务器侧成功之后**一定**要落本地库：它才是界面读的那份数据
                    val localId = store.addDish(saved.name, saved.category, saved.density, emojiFor(saved))
                    loadFromStore()
                    if (localId > 0) State.selected.add(localId.toString())
                    closeOverlay()
                    onDone("已保存菜品库并加入本餐")
                }
            }
        }.start()
    }

    /** 菜品缩略图：数据库里存 emoji；兼容服务器给的 `.svg` 资源名。 */
    private fun emojiFor(food: Food): String =
        if (food.img.endsWith(".svg")) State.emoji[food.img] ?: "🍽" else food.img.ifBlank { "🍽" }
}
