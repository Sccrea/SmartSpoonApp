package com.smartspoon.l2

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

    /** 本次用餐记录的每一口明细。 */
    val currentBites = mutableListOf<Bite>()

    var currentMealStart = 0L
    var currentMealId = 0L
    var currentMealName = "本餐"

    /** 用餐中「添加食物」暂停的时刻（0 = 当前没在暂停），回来时用它把暂停时长扣掉。 */
    var pausedAt = 0L

    /** 勺子数据轮询（无真机时由服务器模拟推送）。 */
    var deviceSeq = 0
    @Volatile var devicePolling = false

    /** 轮询代次：避免旧线程与新线程同时追加数据。 */
    var deviceGeneration = 0

    /** 已收到的最后一口的编号，防止重复计数。 */
    var lastBiteNo = 0

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    /**
     * 用餐计时：每 2.6 秒刷新一次「已用餐时长」。
     * 勺中读数与口数不在这里——那些由服务器模拟推送（见设备轮询）。
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

    /* ------------------------------------------------------------ 设备轮询 */

    /**
     * 用餐中每 1.5 秒轮询一次勺子状态：勺中读数与「每一口」都来自服务器。
     *
     * 搬到共享对象的**关键差别**只有一处：原来用 `Activity.runOnUiThread` 把结果抛回主线程，
     * 这里改用对象自己的主线程 Handler —— 于是轮询不再依赖任何 Activity 还活着。
     */
    fun startPolling() {
        stopPolling()
        devicePolling = true
        deviceSeq = 0
        lastBiteNo = 0
        deviceGeneration++
        val generation = deviceGeneration
        Thread {
            while (devicePolling && generation == deviceGeneration) {
                val json = Api.deviceState(State.server, deviceSeq)
                val state = json?.optJSONObject("state")
                if (state == null) {
                    if (State.deviceOnline) {
                        State.deviceOnline = false
                        handler.post { State.deviceOnline = false }
                    }
                } else {
                    handler.post { applyDeviceState(state) }
                }
                try {
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.start()
    }

    fun stopPolling() {
        devicePolling = false
        deviceGeneration++
    }

    /** 重新开始轮询，但**保留** deviceSeq / lastBiteNo，避免把旧的口重复收一遍。 */
    fun resumePolling() {
        stopPolling()
        devicePolling = true
        deviceGeneration++
        val generation = deviceGeneration
        Thread {
            while (devicePolling && generation == deviceGeneration) {
                val json = Api.deviceState(State.server, deviceSeq)
                val state = json?.optJSONObject("state")
                if (state != null) handler.post { applyDeviceState(state) }
                try {
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.start()
    }

    /** 把服务器发来的勺子状态套用到界面上（在主线程调用）。 */
    fun applyDeviceState(state: org.json.JSONObject) {
        State.deviceOnline = true
        deviceSeq = state.optInt("seq", deviceSeq)
        val meal = State.meal

        val deviceName = state.optString("device", "智味勺")
        val connected = state.optBoolean("connected", true)
        State.data?.devices?.firstOrNull { it.id == State.connectedId }?.let { device ->
            if (device.name != deviceName || device.battery != state.optInt("battery", device.battery)) {
                // 原地改：这两个字段是 Compose 可观察属性，设备页会自动刷新
                device.name = deviceName
                device.battery = state.optInt("battery", device.battery)
            }
        }
        if (!connected) State.connectedId = null

        meal.food = state.optString("food", meal.food)
        meal.spoonWeight = state.optDouble("weight", 0.0).toInt()
        meal.spoonEnergy = state.optDouble("energy", 0.0)

        // 追加服务器新推送的每一口
        val newBites = state.optJSONArray("newBites")
        if (newBites != null && newBites.length() > 0) {
            for (i in 0 until newBites.length()) {
                val bite = newBites.getJSONObject(i)
                val no = bite.optInt("no", 0)
                if (no in 1..lastBiteNo) continue   // 已经收过这一口
                lastBiteNo = maxOf(lastBiteNo, no)
                currentBites.add(
                    Bite(
                        dish = bite.optString("food", meal.food),
                        weight = bite.optDouble("weight", 0.0),
                        energy = bite.optDouble("energy", 0.0),
                        at = System.currentTimeMillis(),
                    )
                )
            }
            meal.bites = currentBites.size
            meal.totalWeight = currentBites.sumOf { it.weight }.toInt()
            meal.totalEnergy = currentBites.sumOf { it.energy }
        }
    }

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
     * 退出记录：停计时、停轮询、告诉服务器停止记录。
     *
     * 阶段 3 起这里**不再管导航**：「回到快速开始首页」是 `LiveActivity.exitMeal()` 的事
     * （它要 `startActivity(MainActivity, CLEAR_TOP)` 才能把整条用餐返回栈清掉）。
     */
    fun exitMeal() {
        State.addingFood = false
        stopTicker()
        stopPolling()
        MealSessionActions.stopRecording()
    }

    /**
     * 换「当前勺中食物」。
     *
     * 必须同步给服务器：轮询每 1.5 秒用服务器那份 food 覆盖本地，
     * 不同步的话这里刚选完就被改回去了。
     */
    fun chooseSpoonFood(food: Food) {
        State.meal.food = food.name
        Thread {
            Api.postDevice(State.server, "/api/device/state", org.json.JSONObject().apply {
                put("food", food.name)
            })
        }.start()
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
        MealSessionActions.stopRecording()
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

        MealSessionActions.resumeRecording()
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
        meal.spoonWeight = 0
        meal.spoonEnergy = 0.0
        say("已记录第 ${currentBites.size} 口")
    }

    /** 结束用餐：停计时与轮询、落库这一餐、组装结果页。 */
    fun finishMeal() {
        stopTicker()
        stopPolling()
        MealSessionActions.stopRecording()
        val meal = State.meal
        val endedAt = System.currentTimeMillis()
        val startedAt = if (currentMealStart > 0) currentMealStart else endedAt - 60_000L
        val minutes = ((endedAt - startedAt) / 60000L).toInt().coerceAtLeast(1)

        // 落库：没有手动记录时，用模拟数据兜底，保证记录非空
        if (currentBites.isEmpty()) {
            val weight = if (meal.totalWeight > 0) meal.totalWeight.toDouble() else 20.0
            currentBites.add(Bite(dish = meal.food, weight = weight, energy = weight * 2.4, at = endedAt))
        }
        currentMealId = db.saveMeal(currentMealName, startedAt, endedAt, currentBites)
        db.bumpTimes(State.selected.toList())

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
        )
    }

    /** 结果页「完成」：把「修正数据」写回记录、重读本地库（回主界面由 `ResultActivity` 负责）。 */
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