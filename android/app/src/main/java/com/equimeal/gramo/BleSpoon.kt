package com.equimeal.gramo

import android.content.Context
import android.util.Log

/**
 * 蓝牙与整个应用之间的**接线板** —— 把 [SpoonLink] 收到的一帧数据落到 [State] /
 * [MealSession] 上，并把「用户点了归零 / 连接 / 断开」翻译成 BLE 命令。
 *
 * ## 为什么要有这一层（而不是让 SpoonLink 直接改 State）
 *
 * - [SpoonLink] 只认识 GATT 与协议，不知道什么叫"这一餐"、什么叫"当前勺中食物"；
 *   它可以在没有任何界面、没有任何用餐会话时独立工作（比如在设备管理页只做连接测试）。
 * - [AppCore] 是应用级单例，但它是"本地库 + 弹窗 + 服务器"的汇合点，不适合再塞 BLE。
 *
 * 两边通过这里的 [SpoonLink.Listener] 接起来，**唯一一份**注册在 [attach] 里，
 * 于是不管用户从哪个页面连上勺子，全局状态都是同一份。
 *
 * ## 数据优先级（真机优先，模拟兜底）
 *
 * 勺中数据的唯一来源就是**真勺子**（BLE 一帧 100ms）。
 *
 * 这里以前还有一条"服务器模拟器"的退路（没连勺子时轮询 `/api/device/state`），
 * 以及一个 App 内的"模拟勺子"自测开关 —— 两者都已删除：界面上出现的数据要么来自真勺子，
 * 要么就是没有数据，不存在"看起来像真的、其实是编的"这第三种状态。
 */
object BleSpoon : SpoonLink.Listener {

    private const val TAG = "BleSpoon"

    /** [mode] 取值：真机蓝牙。 */
    const val MODE_BLE = "ble"

    /** 「最近连过的那台勺子」是否已经尝试过自动连接（一次进程只试一次，避免死循环）。 */
    private var autoConnectTried = false

    /**
     * 挂上应用上下文并开始监听勺子。由 [AppCore.attach] 调用（`Application.onCreate`），
     * 早于任何 Activity —— 于是"用餐中"被系统回收重建、或用户从别的页面进来，连接都还在。
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        SpoonLink.attach(app)
        SpoonLink.autoReconnect = true
        SpoonLink.say = { message -> AppCore.toast(message) }
        SpoonLink.addListener(this)
        // 注意：这里**不**调 State.load(context) —— 那是 MainActivity.onCreate 的事，
        // 每个进程只做一次，避免"设置被读两遍"这种看起来无害、排查起来很烦的双份初始化。
    }

    /**
     * 启动时回连上次用过的勺子。
     *
     * ## 为什么不再有开关
     *
     * 这里原来会先看 `State.autoConnect`（「设置 → 设备管理 → 自动连接」）。
     * 现在**自动回连是固定行为**：用户连过一次勺子，下次打开应用就应该能直接用，
     * 让他先去设置里打开一个开关没有道理 —— 那个开关唯一的效果是"我故意不想自动连"，
     * 而想不连随时可以直接关掉勺子电源。设置页里那一个开关因此去掉了。
     *
     * 只在有"上次连过的地址"时才连，且一次进程只试一次（避免失败后反复重连）。
     * 放在 [MainActivity.onCreate] 里调而不是 [attach]：`Application` 起来的时候
     * 用户可能根本没打算用勺子，等他真的打开应用再占用蓝牙更合理，也更容易解释
     * （"一打开应用就连上了"）。
     */
    fun autoConnectIfRemembered(context: Context) {
        if (autoConnectTried) return
        autoConnectTried = true
        val address = State.bleSpoonAddress
        if (address.isBlank()) return

        when {
            !SpoonLink.hasBluetooth() -> {
                State.bleHint = "这台设备没有蓝牙，无法连接智味勺"
                return
            }
            /*
             * 权限没给 / 蓝牙没开：**启动这一步刻意不弹系统窗口**。
             *
             * 理由：这是应用刚打开、用户还没做任何操作的时刻，一个"智味勺请求开启蓝牙"
             * 的系统弹窗会平白打断他（他甚至没打算现在用勺子）。
             * 请求打开蓝牙的时机统一放在**用户主动点扫描 / 点某台勺子连接**的时候
             * （见 [prepare] 与各页面的 `ble.ensureReady`）—— 那才是他期望它的时刻。
             *
             * 这里只把一个"为什么没连上"的原因写进提示，用户看到后知道去开蓝牙；
             * 开了之后下次启动（或用餐流程里点扫描）就会自动连上。
             */
            !State.hasBlePermissions(context) || !SpoonLink.isBluetoothOn() -> {
                Log.i(TAG, "启动自动回连跳过：${if (!State.hasBlePermissions(context)) "未授予权限" else "蓝牙未开启"}")
                State.bleHint = if (!State.hasBlePermissions(context)) {
                    "需要蓝牙权限才能连接智味勺：在用餐流程里点「扫描」时会请你授权"
                } else {
                    "蓝牙未开启：点「扫描」时会请你打开蓝牙"
                }
                return
            }
        }
        Log.i(TAG, "自动回连 $address")
        SpoonLink.connect(address, State.bleSpoonName)
    }

    /**
     * 用户主动连接一台勺子（设备页 / 连接弹窗点的那一行）。
     * 连上之后会记住它，下次自动回连。
     *
     * ## 为什么需要 [host] 参与
     *
     * 手机侧"要点系统弹窗"的两件事（申请蓝牙权限、请求打开蓝牙）都**必须由当前 Activity 发起**，
     * 而且 `registerForActivityResult` 要求注册发生在该 Activity `STARTED` 之前。
     * 所以这里不能自己 `new` 一个 [BlePermissions]（点按钮时页面早就 RESUMED 了，
     * 那样会抛异常、被吞掉，表现就是"点连接没反应"——现场踩过）。
     *
     * 正确做法是让**宿主页面**把它自己那个在字段初始化时就建好的 [BlePermissions] 传进来
     * （见 [BleReady]），由它去要权限 / 请用户开蓝牙。
     */
    fun connect(context: Context, host: BleReady, address: String, name: String) {
        if (!prepare(context, host) { SpoonLink.connect(address, name) }) return
        SpoonLink.connect(address, name)
    }

    /**
     * 宿主页面提供"权限 + 蓝牙开关"就绪能力的方式。
     *
     * 各页面（`DeviceSettingsActivity` / `MainActivity` / `BaseMealActivity`）都已经持有一个
     * **饿汉式**建好的 `BlePermissions`，把它包成这个接口传进来即可 ——
     * 这样"要权限/开蓝牙"始终由正确的 Activity 发起，不会踩上面那个生命周期坑。
     */
    fun interface BleReady {
        fun ensureReady(onReady: () -> Unit)
    }

    /**
     * 用户主动断开。
     *
     * 只调 [SpoonLink.disconnect] 就够了：它会发出一次 IDLE 的连接事件，
     * [onSpoonConnection] 里再把"在线"熄灭并通知 [MealSession] —— 不要再手工重复一遍，
     * 否则同一次断开会有两条路径改同一批状态（现在幂等，将来很容易变成双份副作用）。
     */
    fun disconnect() {
        SpoonLink.disconnect()
    }

    /**
     * 权限与蓝牙开关的**统一检查**（用户主动连接时走它）。
     *
     * 环境没就绪时**会把用户送到对应的系统弹窗**（缺权限 → 权限弹窗；蓝牙没开 →
     * 「请求打开蓝牙」弹窗），并在就绪之后自动把这次连接补上 —— 用户点一下「允许」就真的连上了，
     * 不用再点第二次。返回 false 表示"这一下先别往下走，等用户在系统弹窗里处理"。
     *
     * 为什么这里要主动请求而不是只弹一句提示：现场反馈过"应用似乎没有请求打开蓝牙" ——
     * 手动连接走的就是这个方法，只给一句 toast 的话，用户还得自己退出去拉通知栏开蓝牙，
     * 然后再回来点一次。
     *
     * 注意：**启动时的自动回连不走这里**（见 [autoConnectIfRemembered] 的注释）：
     * 那是用户没操作的时刻，不该弹系统窗口打断他。
     */
    fun prepare(context: Context, host: BleReady, ready: () -> Unit): Boolean {
        if (!SpoonLink.hasBluetooth()) {
            State.bleHint = "这台设备没有蓝牙"
            AppCore.toast(State.bleHint!!)
            return false
        }
        if (!State.hasBlePermissions(context) || !SpoonLink.isBluetoothOn()) {
            // 交给宿主页面的 BlePermissions：它先要权限、再（必要时）弹系统「请求打开蓝牙」，
            // 全都就绪后回调 ready
            Log.i(TAG, "连接前环境未就绪，请宿主页面申请权限/打开蓝牙")
            host.ensureReady(ready)
            return false
        }
        return true
    }

    /** 扫描到的勺子按信号强度排序（强的在前，用户基本就想连最近那台）。 */
    fun detectedSorted(): List<BleSpoonDevice> =
        SpoonLink.detected.sortedByDescending { it.rssi }

    /* ------------------------------------------------------------ 回调实现 */

    override fun onSpoonStatus(s: SpoonProtocol.Status, seq: Int) {
        // 真机数据到了：只需要把"在线"点亮，读数本身由 MealSession 统一套用
        if (!State.deviceOnline) State.deviceOnline = true
        if (State.bleHint != null && SpoonLink.ready) State.bleHint = null
        MealSession.applyBleStatus(s, seq)
        activeDevice()?.let { device ->
            if (s.battery in 0..100 && device.battery != s.battery) device.battery = s.battery
        }
    }

    override fun onSpoonBite(weight: Double, mark: Int) {
        State.deviceOnline = true
        MealSession.onSpoonBite(weight, mark)
    }

    /**
     * 读到电量：落到"这台设备"那一行上。
     *
     * 这一行可能还没建（连接弹窗里扫到的那台就是刚建的），所以统一走 [deviceRowFor] 的兜底 ——
     * 它找不到设备时会先补进列表。这样"连接智味勺"里那台勺子的电量就能实时刷新出来。
     */
    override fun onSpoonBattery(address: String, level: Int) {
        if (level !in 0..100) return
        detectedSorted().firstOrNull { it.address == address }?.let { deviceRowFor(it) }
        State.data?.devices?.firstOrNull { it.id == address }?.let { row ->
            if (row.battery != level) row.battery = level
        }
        // 当前连着的那台也要同步（设备管理页的"勺子状态"读的是 SpoonLink.battery）
        if (address == SpoonLink.connectedAddress) activeDevice()
    }

    override fun onSpoonEvent(event: SpoonProtocol.Event) {
        Log.d(TAG, "勺子应答: ${event.kind} ${event.fields.joinToString(",")}")
        /*
         * 固件版本单独抬到 INFO：它直接回答"勺子里烧的是哪一版固件"这个现场问题 ——
         * 排查"吃了不记口"时第一件要确认的就是它：1.2 起记口改由 BodySense 自动判定
         * （`B` 帧带的是"吃前 − 吃后"的净重），1.1 及以前是按键触发、报的是本轮峰值。
         * 两者在 App 侧走同一条解析路径，但**没有串口日志时**只能靠版本号判断该往哪边查。
         */
        if (event.kind == 'A' && event.fields.firstOrNull()?.equals("VER", true) == true) {
            Log.i(TAG, "勺子固件版本 = ${event.fields.getOrNull(1) ?: "未提供"}")
        }
    }

    override fun onSpoonConnection(state: SpoonLink.Status, name: String, message: String) {
        State.bleStatus = state
        State.bleHint = when (state) {
            SpoonLink.Status.FAILED -> message
            SpoonLink.Status.SCANNING, SpoonLink.Status.CONNECTING -> message
            else -> null
        }
        when (state) {
            SpoonLink.Status.CONNECTED -> {
                Log.i(TAG, "已连接 $name ($message)")
                State.deviceOnline = true
                val address = SpoonLink.connectedAddress
                val device = activeDevice()
                if (device != null) {
                    State.connectedId = device.id
                    // 连上就当成"已保存"并记住地址：下次打开应用自动回连。
                    // 用户仍可以在设备管理里点「取消保存」把它从列表里去掉（去掉后不再自动回连）。
                    device.saved = true
                    AppCore.appContext?.let { ctx ->
                        State.rememberSpoon(ctx, address, device.name)
                        if (!State.savedBle.contains(address)) State.setSpoonSaved(ctx, address, true)
                    }
                }
                MealSession.onSpoonConnected()
                /*
                 * 连上就在屏幕下方弹一句提示。
                 *
                 * 用户明确要求这一条，因为它解决的是一件真实的不确定：**启动时自动回连是静默的**
                 * （App 一打开就去连上次那台勺子），如果不说一声，用户不知道现在到底连上没有 ——
                 * 只能去「设置 → 设备管理」或用餐流程里找线索。手动连接时这一句同样是即时反馈。
                 */
                AppCore.toast("智味勺已连接：${device?.name ?: name.ifBlank { "智味勺" }}")
            }
            SpoonLink.Status.FAILED, SpoonLink.Status.IDLE -> {
                when (state) {
                    SpoonLink.Status.FAILED -> Log.w(TAG, "连接失败：$message")
                    else -> Log.i(TAG, "已断开：$message")
                }
                State.deviceOnline = false
                MealSession.onSpoonDisconnected()
            }
            else -> Unit
        }
    }

    /* -------------------------------------------------------------- 设备表 */

    /**
     * 当前"勺中数据"来自哪里：
     * - [MODE_BLE]：真机蓝牙连着；
     * - null：**没有勺子**，界面上不该显示任何读数。
     *
     * 返回值保留成可空，是为了让调用点必须显式处理"没有勺子"这个情况 ——
     * 以前这里会返回"自测（模拟勺子）"或让上层回落到服务器模拟器，
     * 于是"没勺子"这个状态很方便被糊过去，现在糊不过去了。
     */
    fun mode(): String? = if (SpoonLink.ready) MODE_BLE else null

    /**
     * 当前连接的真机在 [State.data] 里的那一行（没有就创建）。
     *
     * 为什么要写进 `data.devices`：设备管理页、连接弹窗、"当前已连接: xxx"、
     * 「断开连接」按钮读的都是这一份列表。真机连上时补进去，整棵界面就自然通了，
     * 不用再加一条"真机列表"的旁路。
     */
    fun activeDevice(): Device? {
        val address = SpoonLink.connectedAddress
        if (address.isBlank()) return null
        return upsertDevice(
            id = address,
            name = friendlyName(SpoonLink.connectedName, address),
            battery = SpoonLink.battery.coerceAtLeast(0),
            saved = true,
        )
    }

    /** 在设备列表里找到（或补上）一行蓝牙设备。 */
    private fun upsertDevice(id: String, name: String, battery: Int, saved: Boolean): Device? {
        // 用户改过名字就用他的：扫描/连接刷新不该把自定义名字覆盖回广播名
        val display = State.bleNames[id]?.takeIf { it.isNotBlank() } ?: name
        val bootstrap = State.data ?: return Device(
            id = id,
            name = display,
            battery = battery,
            saved = saved,
            nearby = true,
            picker = true,
            protocol = Device.PROTOCOL_BLE,
        )
        bootstrap.devices.firstOrNull { it.id == id }?.let { existing ->
            if (existing.name != display) existing.name = display
            if (battery in 0..100 && existing.battery != battery) existing.battery = battery
            return existing
        }
        val created = Device(
            id = id,
            name = display,
            battery = battery,
            saved = saved,
            nearby = true,
            picker = true,
            protocol = Device.PROTOCOL_BLE,
        )
        bootstrap.devices.add(created)
        return created
    }

    /** 扫描结果 → 设备表里的一行（"附近的智味勺"要显示它）。 */
    fun deviceRowFor(spoon: BleSpoonDevice): Device? {
        val bootstrap = State.data ?: return null
        val display = State.bleNames[spoon.address]?.takeIf { it.isNotBlank() } ?: spoon.displayName
        bootstrap.devices.firstOrNull { it.id == spoon.address }?.let { existing ->
            if (existing.name != display) existing.name = display
            // 电量实时跟上（-1 = 还没读到，别拿它覆盖已知值）
            if (spoon.battery in 0..100 && existing.battery != spoon.battery) {
                existing.battery = spoon.battery
            }
            if (spoon.address == State.connectedId && !existing.saved) existing.saved = true
            return existing
        }
        val created = Device(
            id = spoon.address,
            name = display,
            // 电量要连上读一次才知道；没读到就是 -1，界面不显示（而不是假装 100%）
            battery = spoon.battery,
            // 扫到就算"用过"：Android 12+ 拿不到 MAC，这台是当前用户自己的勺子，
            // 不保存的话下次连接会从列表里消失（见 file header 的说明）
            saved = true,
            nearby = true,
            picker = true,
            protocol = Device.PROTOCOL_BLE,
        )
        bootstrap.devices.add(created)
        return created
    }

    /** 广播名可能为空（只带了服务 UUID 的设备），退回用 MAC 显示。 */
    private fun friendlyName(name: String, address: String): String =
        if (name.isBlank() || name.equals(SpoonProtocol.DEVICE_NAME, ignoreCase = true)) {
            "智味勺 ${address.takeLast(5)}"
        } else {
            name
        }
}
