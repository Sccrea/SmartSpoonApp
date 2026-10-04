package com.equimeal

import android.os.Handler
import android.os.Looper

/**
 * 一次用餐的会话状态 —— 阶段 2 从 `MainActivity` 搬出来的第一批**状态与计时**。
 *
 * 为什么要搬：这些字段描述的是"这一餐"本身，而不是"哪个 Activity 在显示它"。
 * 留在 Activity 里的话，「用餐中」一旦拆成独立 Activity，两个 Activity 就会各持一半
 * （暂停时刻、已收口数、轮询代次…），记录必然错乱。搬到这里之后两边共用同一份。
 *
 * 这里**刻意不持有 Context / Activity**：只有一个自己创建的主线程 Handler，
 * 所以任何 Activity 在后台被销毁都不会影响正在进行的记录。
 */
object MealSession {

    /**
     * 读数来源：**真机蓝牙**。
     *
     * 这里现在只剩这一个来源 —— 服务器模拟勺子（`/api/device/…`）与 App 内的"模拟勺子"自测
     * 都已删除：勺子的数据只可能来自真勺子，界面上不必再判断"这数据是真的还是模拟的"。
     * 保留这个常量是因为「用餐中」页面上那一行说明文字还在用它做文案分支。
     */
    const val SOURCE_BLE = "ble"

    /**
     * 自定义菜单的默认名字。
     *
     * 独立成常量是因为它被三处用到、必须完全一致：写入时的默认值、
     * 判断"要不要问用户起名"的条件、以及弹窗里输入框的预填。
     */
    const val DEFAULT_CUSTOM_MEAL_NAME = "自定义菜单"

    /** 本次用餐记录的每一口明细。 */
    val currentBites = mutableListOf<Bite>()

    var currentMealStart = 0L
    var currentMealId = 0L
    var currentMealName = "本餐"

    /** 用餐中「添加食物」暂停的时刻（0 = 当前没在暂停），回来时用它把暂停时长扣掉。 */
    var pausedAt = 0L

    /**
     * **真机**口数的基准（固件的 `mark`）。
     *
     * `mark` 是"开机以来标记键按了多少次"（可能从 37 开始），不是"这一餐的第几口"，
     * 所以手机侧要自己用增量算口数。
     */
    private var lastSpoonMark = -1

    /** 本轮舀取过程里舀到过的最大重量（g）：报"一口"时如果已经空了，用它当这一口的重量。 */
    private var peakSpoonWeight = 0.0

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    /**
     * 用餐计时：每 2.6 秒刷新一次「已用餐时长」。
     * 勺中读数与口数不在这里 —— 那些由真勺子通过蓝牙推上来。
     */
    fun startTicker() {
        stopTicker()
        val runnable = object : Runnable {
            override fun run() {
                val meal = State.meal
                // 阶段 3：「用餐中」已是独立 Activity，不能再靠 `State.screen is Screen.Live`
                // 判断。计时器本来就只在用餐期间运行（进入用餐时 startTicker、退出/结束时 stopTicker），
                // 所以「没暂停」就是它要刷新的全部条件。
                if (!meal.paused) {
                    meal.minutes = ((System.currentTimeMillis() - meal.startedAt) / 60000).toInt()
                        .coerceAtLeast(1)
                }
                handler.postDelayed(this, 2600)
            }
        }
        ticker = runnable
        handler.postDelayed(runnable, 2600)
    }

    fun stopTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    /* ------------------------------------------------------------ 设备数据 */

    /**
     * 开始收取勺子数据。
     *
     * 现在只有一条数据来源：**真机蓝牙**（[SpoonLink]）。这个方法因此变成"重置口数基准"，
     * 真正收数据的是 [BleSpoon] 注册在 [SpoonLink] 上的那条回调链 ——
     * 界面里的调用点（用餐提醒的「我已知晓」）不需要知道这个变化。
     *
     * 名字保留 `startPolling/stopPolling` 是为了不动所有调用点；
     * 它们现在表达的是"开始/结束接收这一餐的勺子数据"。
     */
    fun startPolling() {
        lastSpoonMark = -1
        peakSpoonWeight = 0.0
        State.deviceOnline = SpoonLink.ready
    }

    fun stopPolling() {
        State.deviceOnline = false
    }

    /** 与 [startPolling] 对称：不动已经累计的口数，只保证数据源仍然是真机。 */
    fun resumePolling() {
        State.deviceOnline = SpoonLink.ready
    }

    /* -------------------------------------------------------- 真机（BLE）侧 */

    /** 真机连上：把"在线"点亮，口数基准重置（换了一台/刚连上，不能补记历史）。 */
    fun onSpoonConnected() {
        lastSpoonMark = -1
        peakSpoonWeight = 0.0
        State.deviceOnline = true
    }

    /**
     * 真机断开。
     *
     * 以前这里会"回落到服务器模拟器继续记"，现在没有模拟器了 ——
     * 断开就是断开：界面会明确说"勺子未连接、读数暂停"，用户重新连上即可。
     * 正在进行的这一餐不会因此丢失（已经记下的口都在 [currentBites] 里）。
     */
    fun onSpoonDisconnected() {
        State.deviceOnline = false
    }

    /**
     * 真机的一帧状态（在主线程调用，来自 [BleSpoon]）。
     *
     * 只管"勺中实时读数"；口数由 [onSpoonBite] 单独处理
     * （固件的 mark 是单调计数，一帧一跳）。
     */
    fun applyBleStatus(s: SpoonProtocol.Status, seq: Int) {
        State.deviceOnline = true
        val meal = State.meal
        meal.spoonWeight = s.weight10 / 10.0
        meal.spoonEnergy = energyFor(meal.spoonWeight)
        /*
         * 记下"刚舀起来最多有多少"。
         *
         * 报"一口"的时刻往往已经空了（读数回到 0），而 `SpoonLink` 那边的兜底值可能也已经被
         * 后面的 0 覆盖掉；这里保留本轮的最大值，[onSpoonBite] 才有真实重量可落库。
         */
        if (meal.spoonWeight > peakSpoonWeight) peakSpoonWeight = meal.spoonWeight

        val device = State.connectedDevice()
        if (device != null && s.battery in 0..100) device.battery = s.battery
    }

    /**
     * 真机报来"一口"（勺子上的标记键，或固件侧 mark 计数增加）。
     *
     * 与 [recordBite] 的差别只在**重量来源**：手动记录允许读数还没稳定就用默认值，
     * 这里必须有依据才算一口 —— 否则会把 0 g 记成一口 15 g 的假数据。
     *
     * 三级兜底：固件给的重量 → 当前读数 → 本轮舀取过程的最大值（[peakSpoonWeight]）。
     * 三个都是 0 时才拒绝，并把这件事说清楚（而不是静默吞掉）。
     */
    fun onSpoonBite(weight: Double, mark: Int) {
        // 去重由 SpoonLink 负责（B 事件帧与状态帧里的 mark 增量都收敛成一次回调），
        // 这里只做"这台勺子的 mark 是不是往回退了（重新上电/换了一台）"的兜底
        if (mark >= 0) {
            if (lastSpoonMark >= 0 && mark < lastSpoonMark) lastSpoonMark = -1
            lastSpoonMark = maxOf(lastSpoonMark, mark)
        }

        val meal = State.meal
        val grams = when {
            weight > 0.5 -> weight
            meal.spoonWeight > 0.5 -> meal.spoonWeight
            else -> peakSpoonWeight
        }
        if (grams <= 0.5) {
            android.util.Log.w(
                "MealSession",
                "第 $mark 口没有可用重量被忽略: 固件给的=$weight 当前读数=${meal.spoonWeight} 本轮峰值=$peakSpoonWeight",
            )
            say("勺子报来第 $mark 口，但没有读到重量（0 g），已忽略")
            return
        }
        val energy = energyFor(grams)
        currentBites.add(Bite(dish = meal.food, weight = grams, energy = energy, at = System.currentTimeMillis()))
        meal.bites = currentBites.size
        meal.totalWeight = currentBites.sumOf { it.weight }.toInt()
        meal.totalEnergy = currentBites.sumOf { it.energy }
        meal.spoonWeight = 0.0
        meal.spoonEnergy = 0.0
        peakSpoonWeight = 0.0
        android.util.Log.i(
            "MealSession",
            "已记录第 ${currentBites.size} 口（mark=$mark）: ${"%.1f".format(grams)}g / ${"%.1f".format(energy)}kJ",
        )
        say("已记录第 ${currentBites.size} 口：${"%.1f".format(grams)} g")
    }

    /**
     * 勺中读数 → 热量：用**当前勺中食物**的能量密度，没有就用 2.4 kJ/g 的通用值。
     *
     * ## 为什么必须按"当前勺中食物"算，而不是按本餐第一道菜
     *
     * 这里原来读的是 `State.selected.firstOrNull()` —— 也就是**这一餐选的第一道菜**。
     * 于是用餐中把"当前勺中食物"从（比如）黄瓜切换成烤鸭之后，
     * 界面上的实时热量仍然按黄瓜的 0.16 kJ/g 算（`meal.food` 已经变成烤鸭了），
     * 显示的数值与用户刚选的东西对不上；同一口落库时又会用烤鸭的密度算一次，
     * 结果"实时读数"和"记下来的那一口"两个数不一致。
     *
     * 现在按 `meal.food`（当前勺中食物）查密度，切换立刻生效，三处（实时读数、记口、结果页）
     * 用的是同一个密度。
     */
    private fun energyFor(grams: Double): Double {
        if (grams <= 0) return 0.0
        val density = currentFoodDensity()
        return grams * (if (density > 0) density else 2.4)
    }

    /** 当前勺中食物的能量密度（kJ/g）；没选/查不到时返回 0，由调用方回落到通用值。 */
    private fun currentFoodDensity(): Double =
        State.data?.foods?.firstOrNull { it.name == State.meal.food }?.density ?: 0.0

    /* -------------------------------------------------------- 餐次生命周期 */

    /**
     * 提示消息的出口：由 [AppCore] 在 `Application.onCreate` 里挂好。
     *
     * 阶段 2 时它是「当前 Activity 在 `onCreate` 里挂上自己」，阶段 3 拆出 4 个 Activity 后
     * 那样会指向已经销毁的宿主；改成应用级之后，[MealSession] 依然**不持有 Context**，
     * 但提示出口与「谁在前台」彻底无关了。
     */
    var toastSink: (String) -> Unit = {}

    private fun say(message: String) = toastSink(message)

    /**
     * 退出记录：停计时、停收数据。
     *
     * 阶段 3 起这里**不再管导航**：「回到快速开始首页」是 `LiveActivity.exitMeal()` 的事
     * （它要 `startActivity(MainActivity, CLEAR_TOP)` 才能把整条用餐返回栈清掉）。
     */
    fun exitMeal() {
        State.addingFood = false
        stopTicker()
        stopPolling()
    }

    /**
     * 换「当前勺中食物」。
     *
     * 只改本地 —— 以前还要同步给服务器模拟器（否则 1.5 秒一次的轮询会把刚选的食物覆盖回去），
     * 现在没有服务器轮询了，这一句就是全部。
     */
    fun chooseSpoonFood(food: Food) {
        State.meal.food = food.name
        say("当前勺中食物：${food.name}")
    }

    /** 用餐中出来加菜：暂停记录（并记下暂停时刻）。打开加菜页由 `LiveActivity` 负责。 */
    fun addFoodDuringMeal() {
        val meal = State.meal
        if (!meal.paused) {
            meal.paused = true
            // 记下暂停时刻，回来时把暂停这段时长从「已用餐时长」里扣掉
            pausedAt = System.currentTimeMillis()
        }
        stopPolling()
        State.addingFood = true
        say("已暂停记录，添加完菜品后返回继续")
    }

    /** 加完菜返回：把暂停时长扣掉并恢复记录（`FoodPickerActivity` 随后关掉自己回到「用餐中」）。 */
    fun finishAddingFood() {
        if (!State.addingFood) return
        State.addingFood = false

        val meal = State.meal
        // 把暂停期间的时间从起点往后推，时长不会把暂停算进去
        if (pausedAt > 0 && meal.startedAt > 0) {
            meal.startedAt += System.currentTimeMillis() - pausedAt
            currentMealStart += System.currentTimeMillis() - pausedAt
        }
        pausedAt = 0L
        meal.paused = false

        resumePolling()
        say("已恢复记录")
    }

    /** 用餐结果页：「点击查看每口详细数据」。 */
    fun showBiteDetail() {
        State.dialog = AppDialog.BiteDetail
    }

    /* ---------------------------------------------- 需要本地库的那几个 */

    /**
     * 本地库与「重读本地库」的挂钩：由 [AppCore] 在 `Application.onCreate` 里一次挂好。
     *
     * 用挂钩而不是 `Context`，是为了两件事：
     * 1. 共享层继续不持有 Context；
     * 2. 全应用**只有一份** `Store` 实例（[AppCore.store]），不必担心两个连接同时写。
     */
    var storeProvider: (() -> Store)? = null
    var reloadSink: () -> Unit = {}

    /**
     * 「这一餐落库了，去和云端对一下」的挂钩（由 [AppCore] 挂上）。
     *
     * 用餐记录是**按账号存在云端**的，所以刚结束的这一餐要尽快推上去；
     * 未登录时 [AppCore.syncCloudData] 会直接返回，什么也不做。
     */
    var cloudSyncSink: () -> Unit = {}

    private val db: Store
        get() = requireNotNull(storeProvider) { "MealSession.storeProvider 尚未挂载" }.invoke()

    /** 「记录一口」：把当前勺中读数记成一口，并刷新合计。 */
    fun recordBite() {
        val meal = State.meal
        val weight = if (meal.spoonWeight > 0) meal.spoonWeight.toDouble() else 15.0
        val energy = if (meal.spoonEnergy > 0) meal.spoonEnergy
        else weight * (State.food(State.selected.firstOrNull())?.density ?: 2.0)
        currentBites.add(
            Bite(dish = meal.food, weight = weight, energy = energy, at = System.currentTimeMillis())
        )
        meal.bites = currentBites.size
        meal.totalWeight = currentBites.sumOf { it.weight }.toInt()
        meal.totalEnergy = currentBites.sumOf { it.energy }
        meal.spoonWeight = 0.0
        meal.spoonEnergy = 0.0
        say("已记录第 ${currentBites.size} 口")
    }

    /**
     * 结束用餐：停计时与收数、落库这一餐、组装结果页。
     *
     * **不会编造数据**：这一餐一口都没记下（勺子没连上、或用户没舀过东西）时，
     * 记录里的口数就是 0、重量与热量就是 0。以前这里会在"没有手动记录时用模拟数据兜底"
     * 塞进一口 20 g 的假数据，好让记录看起来不为空 —— 那是模板行为，已经去掉：
     * 一份自己没吃过的记录比一份空记录糟糕得多。
     */
    fun finishMeal() {
        stopTicker()
        stopPolling()
        val meal = State.meal
        val endedAt = System.currentTimeMillis()
        val startedAt = if (currentMealStart > 0) currentMealStart else endedAt - 60_000L
        val minutes = ((endedAt - startedAt) / 60000L).toInt().coerceAtLeast(1)

        currentMealId = db.saveMeal(currentMealName, startedAt, endedAt, currentBites)
        db.bumpTimes(State.selected.toList())
        // 记录与食用次数都变了：推给云端（未登录时这一步是空操作）
        cloudSyncSink()

        /*
         * 这一餐叫什么，现在**在用餐结果页显示并可点击修改**（`MealResult.mealName` + `AppDialog.MealName`）。
         *
         * 这里以前的做法是：如果名字是「自定义菜单」就立刻弹一个"起个名字"的对话框。
         * 改成结果页编辑有两个好处：
         * - 用户先看到结果（吃了多少、几口），再决定叫什么，顺序更自然，也不会挡住结果页；
         * - 用「使用现有菜单快速开始」的那一餐**也能改名**了 —— 以前只有自定义菜单能改。
         */

        val bites = currentBites.size
        val weight = currentBites.sumOf { it.weight }
        val energy = currentBites.sumOf { it.energy }
        // 存**数值 + 基准单位**（分钟 / 口 / g / kJ），显示时由 Units 按当前设置换算
        State.result = MealResult(
            savedNo = (State.data?.records?.size ?: 0) + 1,
            minutes = minutes,
            bites = bites,
            weightGrams = weight,
            energyKj = energy,
            avgWeightGrams = weight / bites,
            avgEnergyKj = energy / bites,
            // 结果页要显示"这一餐叫什么"并允许点击修改，所以名字跟着结果一起带走
            mealName = currentMealName,
        )
    }

    /**
     * 给这一餐改名（结果页点那一行 → 弹窗保存）。
     *
     * 名字为空时回落到当前名字（而不是硬编码「自定义菜单」）：这个弹窗现在两种用餐都能用，
     * 把"用现有菜单快速开始"的那一餐清空后按默认名保存，不该把它改成「自定义菜单」。
     */
    fun renameMeal(name: String): String {
        val fallback = currentMealName.ifBlank { DEFAULT_CUSTOM_MEAL_NAME }
        val trimmed = name.trim().ifBlank { fallback }
        currentMealName = trimmed
        // MealResult 是可变的 Compose 状态持有类（不是 data class），所以直接改字段
        State.result.mealName = trimmed
        if (currentMealId > 0) db.renameMeal(currentMealId, trimmed)
        reloadSink()
        return "已命名为「$trimmed」"
    }
    fun doneResult() {
        // 把「修正数据」写回这条用餐记录（现在存的就是基准单位的数值，不再需要从字符串里抠数字）
        if (currentMealId > 0) {
            db.updateMeal(
                currentMealId,
                State.result.bites,
                State.result.weightGrams,
                State.result.energyKj,
            )
        }
        currentBites.clear()
        currentMealId = 0L
        State.selected.clear()
        reloadSink()
        say("用餐记录已保存")
    }

    fun mealBites(mealId: Long): List<Bite> = db.bites(mealId)
}