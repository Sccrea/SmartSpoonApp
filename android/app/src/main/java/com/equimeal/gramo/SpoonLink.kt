package com.equimeal.gramo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 一台**扫描到的**智味勺 —— 只描述"广播里看到的东西"，不含连接状态。
 *
 * 不是 data class 而用 Compose 可观察字段：扫描是持续进行的，同一个 MAC 的 RSSI
 * 每几百毫秒就会变一次，界面应当跟着刷新，而不是每次重建整个列表。
 */
class BleSpoonDevice(
    val address: String,
    name: String,
    rssi: Int,
    val hasService: Boolean,
) {
    var name by mutableStateOf(name)
    var rssi by mutableStateOf(rssi)

    /**
     * 电量（%）。**-1 = 还不知道**。
     *
     * BLE 广播包里没有电量，它要连上之后读标准电量服务（0x180F / 0x2A19）才有；
     * 而"连接智味勺"那个列表里，用户正是想在连之前看到每台勺子剩多少电。
     * 所以这里存"最近一次读到的值"：
     * - 当前连着的那台 → 真实值；
     * - 以前连过的 → 上次读到的值（可能过时，但比"未知"有用，界面上会标注）；
     * - 从没连过的 → -1，界面就不显示电量。
     */
    var battery by mutableStateOf(-1)

    /** 广播里带了 NUS 服务 UUID（比只看名字更可靠）。 */
    var serviceSeen by mutableStateOf(hasService)

    val displayName: String
        get() = name.ifBlank { "智味勺 $address" }
}

/**
 * 智味勺的 BLE 连接管理器 —— **全应用唯一一份**。
 *
 * ## 它负责什么
 *
 * 把「勺子的数据怎么来」这件事收在这一个对象里：扫描 → 连接 → 打开 Notify →
 * 按行解析 → 通过 [Listener] 把**已经解析好的**状态交给上层。
 * 上层（[State] / [MealSession] / 界面）只认识 `weightGrams / stable / mark / battery`，
 * 不认识 GATT、句柄、CCCD 这些东西。
 *
 * ## 为什么是 object（应用级单例）
 *
 * 「用餐中」是独立 Activity，设备管理页是另一个。如果各自持有连接，那么从设备页连上
 * 勺子再进「用餐中」，第二份对象会认为"没连上"而重新扫一遍 —— 现场表现就是
 * 「明明连上了，进了记录页却说未连接」。所以连接状态必须与任何 Activity 的生死无关，
 * 和 [AppCore] / [MealSession] 一样挂在进程上。
 *
 * ## 线程模型（BLE 最容易出错的地方）
 *
 * - **所有状态只允许在主线程改**：`BluetoothGattCallback` 的回调线程不是主线程，
 *   所以每个回调进来第一件事就是 `main.post { … }`。界面拿到的一定是主线程的更新。
 * - 扫描回调同理（它在 binder 线程上），只把结果 post 回主线程再落进
 *   [detected] 这个 `mutableStateListOf`。
 * - 读特征值（电量）用 `gatt.readCharacteristic()` 的**回调**拿值，不在回调里同步读，
 *   因为 Android 的 GATT 栈不允许并发操作。
 *
 * ## 断线与重连
 *
 * 掉线（`STATE_DISCONNECTED`）后如果 [autoReconnect] 为真，会在 [RECONNECT_DELAY_MS]
 * 之后按「最后一次已知地址」直接 `connectGatt`，不回退到扫描 —— 勺子固件
 * （`Bluefruit.Advertising.restartOnDisconnect(true)`）会立刻重新广播，直接连最快，
 * 也免得用户在「用餐中」看着"正在扫描"。
 */
object SpoonLink {

    private const val TAG = "SpoonLink"

    /** 状态帧静默多久算掉线/没数据（固件 100ms 一帧，3 秒已经很宽松）。 */
    private const val STALE_MS = 3_000L

    /** "刚静默过"的判定窗口：mark 在这段时间内一跳多口，认为是静默期间漏的口。 */
    private const val STALE_RECENT_MS = 5_000L

    /** 掉线后自动重连的延迟。 */
    private const val RECONNECT_DELAY_MS = 1_500L

    /** 扫描时长上限（到点自动停，省电）。 */
    const val SCAN_MS = 12_000L

    /** 扫描期间就算没扫到，也最长只会跑这么久。 */
    private const val CONNECT_TIMEOUT_MS = 12_000L

    /* --------------------------------------------------------------- 状态 */

    /** 与 UI 的粗粒度状态机。 */
    enum class Status { IDLE, SCANNING, CONNECTING, CONNECTED, FAILED }

    var status by mutableStateOf(Status.IDLE)
        private set

    /** 人话的当前进度/失败原因（界面直接显示，不用自己拼）。 */
    var statusText by mutableStateOf("未连接")
        private set

    var connectedName by mutableStateOf("")
        private set
    var connectedAddress by mutableStateOf("")
        private set

    /** 最近一次状态帧里的电量（-1 = 还不知道）。 */
    var battery by mutableStateOf(-1)
        private set

    /** 最近一帧的信号强度（dBm）。 */
    var rssi by mutableStateOf(0)
        private set

    /** 每收到一帧 +1，界面可以用它做"数据在动"的指示。 */
    var frames by mutableStateOf(0)
        private set

    /** 有连接但超过 [STALE_MS] 没有状态帧（固件卡死 / 已走远）。 */
    var stale by mutableStateOf(false)
        private set

    /** 最近一次错误（人话）。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 是否已打开通知、能收数据。 */
    val ready: Boolean get() = status == Status.CONNECTED

    /** 扫描到的勺子（RSSI 降序由界面自己排）。 */
    val detected = mutableStateListOf<BleSpoonDevice>()

    var scanning by mutableStateOf(false)
        private set

    /** 自动重连（对齐「设备管理 → 自动连接」那个开关）。 */
    var autoReconnect = true

    /** 收到通知时的回调；全部在主线程调用。 */
    interface Listener {
        fun onSpoonStatus(s: SpoonProtocol.Status, seq: Int) {}
        fun onSpoonBite(weight: Double, mark: Int) {}
        fun onSpoonEvent(event: SpoonProtocol.Event) {}
        fun onSpoonConnection(state: Status, name: String, message: String) {}

        /** 读到某台勺子的电量（[address] 是它的 MAC）。 */
        fun onSpoonBattery(address: String, level: Int) {}
    }

    private val listeners = mutableListOf<Listener>()

    /** 需要跟界面说的"动作失败了"，由 [AppCore] 在 Application 里挂成 toast。 */
    var say: (String) -> Unit = {}

    /* --------------------------------------------------------- 内部字段 */

    private val main = Handler(Looper.getMainLooper())

    private var context: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** 上一次连接用的地址，自动重连用它（不走扫描）。 */
    private var lastAddress: String? = null
    private var wantConnected = false

    /** 收到分片数据的粘包缓冲：NUS 的每一包不保证就是一行（见 [FrameReader]）。 */
    private val rx = FrameReader()

    /** 内容去重用的序号（界面/服务器那套 seq 语义）。 */
    private var seq = 0
    private var lastWeight10 = Int.MIN_VALUE
    private var lastStable = false

    /** 上一次**非零**读数（×10）。报"一口"时如果当前读数是 0，就用它当这一口的重量。 */
    private var peakWeight10 = 0

    /** 已处理的最大 mark（口数去重：`B` 事件帧与 `mark` 增量只算一次）。 */
    private var lastMark = -1

    /** 最近是否刚经历过"数据静默"（用于判断 mark 一跳多口是不是静默期间漏的）。 */
    private var recentStale = false

    /** 上一次"静默结束"的时刻（配合 [recentStale] 用）。 */
    private var staleEndedAt = 0L

    private var lastFrameAt = 0L

    /** 上一次打"状态帧摘要"的时间（见 [applyStatus]；只在 debug 构建用）。 */
    private var lastStatusLogAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (ready) {
                val quiet = System.currentTimeMillis() - lastFrameAt
                val isStale = quiet > STALE_MS
                if (isStale != stale) {
                    stale = isStale
                    if (!isStale) staleEndedAt = System.currentTimeMillis()
                    // 恢复时要**主动**把状态文案改回来，否则会一直停在"3 秒没有数据"上
                    statusText = if (isStale) {
                        "已连接但 ${quiet / 1000} 秒没有数据（勺子可能休眠或走远了）"
                    } else {
                        "已连接，正在接收勺子数据"
                    }
                    /*
                     * 静默与恢复都打日志：现场"吃了却没记到口"最常见的原因是
                     * 记口那一刻正好落在静默窗口里（B 帧丢了），日志里只有这两条能把这件事看出来。
                     */
                    Log.w(
                        TAG,
                        if (isStale) "数据静默 ${quiet}ms（已收 $frames 帧），开始等恢复"
                        else "数据恢复（静默结束，已收 $frames 帧）",
                    )
                    emitConnection()
                }
            }
            main.postDelayed(this, 1_000L)
        }
    }

    private val scanStop = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        val found = detected.size
        if (status == Status.SCANNING) {
            status = if (found == 0) Status.FAILED else Status.IDLE
            // 提示里不再写"点一行连接"：那一行画不画得出来是界面的事，
            // 这里只负责把"扫到几台"说清楚（见 DeviceSettingsScreen 里那两段列表的说明）
            statusText = if (found == 0) {
                "没有发现智味勺，请确认勺子已上电（并检查定位开关是否打开）"
            } else {
                "扫到 $found 台设备"
            }
            emitConnection()
        }
        Log.i(TAG, "扫描结束，共发现 $found 台候选设备")
    }

    private val connectTimeout = Runnable {
        if (status == Status.CONNECTING) {
            fail("连接超时：请确认勺子已上电、在范围内，且没有被别的手机占用")
        }
    }

    /* ---------------------------------------------------------- 生命周期 */

    /**
     * 挂上 Context（只留 `applicationContext`，绝不持有 Activity）。
     * 由 [AppCore.attach] 在 `Application.onCreate` 里调用，早于任何界面。
     */
    fun attach(appContext: Context) {
        context = appContext.applicationContext
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter
        /*
         * `scanner` 这里**不缓存**（保持 null）：首次进入应用时权限往往还没给，
         * `adapter.bluetoothLeScanner` 会抛 SecurityException 或被系统拒绝，
         * 一旦把那个 null 存下来，用户授完权也永远扫不动。统一由 [startScan] 每次现取。
         */
        scanner = null
        Log.i(TAG, "attach: 蓝牙适配器=${if (adapter != null) "有" else "无"}")
        main.removeCallbacks(watchdog)
        main.post(watchdog)
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** 蓝牙是否可用（没有适配器 / 没开蓝牙都返回 false）。 */
    fun isBluetoothOn(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    fun hasBluetooth(): Boolean = adapter != null

    /* -------------------------------------------------------------- 扫描 */

    /**
     * 开始扫描；到 [SCAN_MS] 自动停。调用前请确保权限已拿到（见 [BlePermissions.ensure]）。
     *
     * 这个方法必须**可以随便重复调用**（弹窗打开时会扫一次，用户还会点「重新扫描」，
     * 设备管理页又有自己的按钮）。BLE 的扫描器是"单例资源"：
     * 同一时刻只允许一个扫描，重复 `startScan` 会直接回调
     * `onScanFailed(SCAN_FAILED_ALREADY_STARTED = 1)` —— 界面上就是"点了没反应 / 报错误码 1"。
     * 所以这里自己保证幂等：先停掉上一次，再起新的。
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        val ctx = context ?: return
        error = null

        if (!hasBluetooth()) {
            abortScan("这台设备没有蓝牙")
            return
        }
        if (!isBluetoothOn()) {
            abortScan("蓝牙未开启，请先打开手机蓝牙")
            return
        }
        if (!State.hasBlePermissions(ctx)) {
            abortScan("缺少蓝牙权限，请点「授予蓝牙权限」")
            return
        }
        /*
         * 定位服务：Android 12+ 有了 BLUETOOTH_SCAN 之后**不再需要定位权限**，
         * 但不少 ROM（尤其国内定制系统）仍然要求系统的**定位开关是打开的**，
         * 否则 `startScan` 不报错、也不回调任何结果 —— 现场表现就是"一直转圈，什么都没扫到"。
         * 这个坑没有任何异常可查，只能主动查一眼并明确告诉用户。
         */
        if (!isLocationServiceOn(ctx)) {
            abortScan("请打开手机的定位开关：部分系统即使不索取定位权限，也会因为定位关闭而扫不到蓝牙设备")
            return
        }

        // 每次都重新取一次扫描器：`attach` 那一次如果权限还没给，它会是 null，
        // 而且这个 null 会被永远缓存下来（表现为"授权之后依然扫不动"）。
        val bleScanner = try {
            adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            null
        }
        if (bleScanner == null) {
            abortScan("拿不到蓝牙扫描器，请确认蓝牙已打开并已授予权限")
            return
        }
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            abortScan("这台设备不支持蓝牙低功耗（BLE）")
            return
        }
        scanner = bleScanner

        /*
         * 扫描意味着"要换一台勺子"：先把当前连接断开。
         *
         * 不改的话会出现这种情况：用户已经连着 A 勺子，在「连接智味勺」里点「重新选择」，
         * 于是扫描启动、列表里列出了 B 勺子，但 A 仍然连着 —— 用户点 B 时先断 A 再连 B，
         * 中间那一下"到底现在连着谁"是含糊的；而且列表里 A 那一行还带着"已连接"的选中态，
         * 看起来像"重新选择没生效"。断开的时机放在**扫描开始**最自然：
         * 用户按下"重新选择/重新扫描"就是在表达"我不要现在这台了"。
         */
        if (connectedAddress.isNotBlank()) {
            Log.i(TAG, "开始扫描前断开当前连接：$connectedAddress")
            disconnect()
        }

        // 先把上一次停干净（并让 `scanning` 归位），否则下面那次 startScan 会撞上 ALREADY_STARTED
        stopScan()
        main.removeCallbacks(connectTimeout)

        detected.clear()
        scanning = true
        status = Status.SCANNING
        statusText = "正在扫描附近的智味勺…"
        emitConnection()

        /*
         * **不带 ScanFilter**，全部过滤放在回调里做。
         *
         * 理由：`ScanFilter.setServiceUuid` 是"硬件/协议栈级"的过滤 —— 设备如果没把 NUS 的
         * 128 位 UUID 放进广播包（很多固件因为广播包只有 31 字节而放不下，只放名字），
         * 那么**一条回调都不会来**，界面上就是"怎么扫都扫不到"，而且没有任何错误可查。
         * 自己按"服务 UUID 或名字像勺子"筛，代价是回调多一点，换来的是现场一定能扫到。
         */
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner.startScan(null, settings, scanCallback)
            Log.i(TAG, "开始扫描（无过滤，时长 ${SCAN_MS}ms）")
            // 超时是"无论如何都要收手"的兜底：先排上，再也不用担心某个 ROM 的回调不来
            main.postDelayed(scanStop, SCAN_MS)
        } catch (e: SecurityException) {
            abortScan("缺少蓝牙扫描权限")
        }
    }

    /** 扫描无法开始：把状态说清楚，别让界面停在"正在扫描"。 */
    private fun abortScan(message: String) {
        scanning = false
        status = Status.FAILED
        statusText = message
        error = message
        emitConnection()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        main.removeCallbacks(scanStop)
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "停止扫描失败", e)
        }
    }

    /** 定位服务是否打开（见 [startScan] 里那段说明）。 */
    private fun isLocationServiceOn(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            lm?.isLocationEnabled ?: true
        } else {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                ctx.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF,
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }
    } catch (e: Exception) {
        // 查不到就别拦着用户（有些精简 ROM 没有 LocationManager）
        true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val hasService = record?.serviceUuids?.any { it.uuid == SpoonProtocol.SERVICE } == true
            val name = try {
                record?.deviceName ?: device.name ?: ""
            } catch (e: SecurityException) {
                ""
            }
            val looksLikeSpoon = SpoonProtocol.NAME_HINTS.any { name.lowercase(Locale.ROOT).contains(it) }
            if (!hasService && !looksLikeSpoon) return
            val address = device.address
            val rssi = result.rssi
            Log.d(TAG, "扫到候选设备 $address name=$name rssi=$rssi service=$hasService")
            main.post { upsert(address, name, rssi, hasService) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                when (errorCode) {
                    /*
                     * 1 = SCAN_FAILED_ALREADY_STARTED：**不是失败**。
                     * 上一次扫描还在跑（比如弹窗打开时自动扫了一次，用户马上又点了「重新扫描」）。
                     * 这时"什么都不做"才是对的：数据照样会来，把状态改成 FAILED 反而骗了用户。
                     */
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> {
                        Log.i(TAG, "扫描已经在跑了，忽略这次重复启动")
                        if (!scanning) {
                            scanning = true
                            status = Status.SCANNING
                            statusText = "正在扫描附近的智味勺…"
                            emitConnection()
                            main.removeCallbacks(scanStop)
                            main.postDelayed(scanStop, SCAN_MS)
                        }
                    }
                    // 2 = APPLICATION_REGISTRATION_FAILED（App 注册的扫描回调被系统拒了）
                    2 -> abortScan("系统拒绝了扫描请求，请重启手机蓝牙后重试")
                    // 3 = INTERNAL_ERROR（蓝牙栈内部错误，最常见的就是需要关开一次蓝牙）
                    3 -> abortScan("手机蓝牙栈报内部错误，请关闭再打开一次蓝牙")
                    // 4 = FEATURE_UNSUPPORTED
                    4 -> abortScan("这台设备不支持 BLE 扫描")
                    else -> abortScan("扫描启动失败（错误码 $errorCode）")
                }
            }
        }
    }

    private fun upsert(address: String, name: String, rssi: Int, hasService: Boolean) {
        val existing = detected.firstOrNull { it.address == address }
        if (existing == null) {
            detected.add(BleSpoonDevice(address, name, rssi, hasService))
        } else {
            if (name.isNotBlank()) existing.name = name
            existing.rssi = rssi
            if (hasService) existing.serviceSeen = true
        }
    }

    /* -------------------------------------------------------------- 连接 */

    /**
     * 连接一台勺子（[address] 是 MAC）。已连接的同一台会被忽略。
     *
     * 先停扫描再连：Android 的 BLE 栈在扫描与连接同时进行时容易在部分机型上
     * 出现 `GATT_ERROR 133`，停一下最稳。
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String = "") {
        stopScan()
        val ctx = context ?: return
        error = null
        wantConnected = true
        /*
         * 只有**换了另一台勺子**才清空口数基准。
         *
         * 这里是踩过的坑：原来无条件 `lastMark = -1`，于是每次自动重连（你的手机上大约
         * 每 20 秒就会因为 BLE 静默而重连一次）之后的**第一帧都会被当成"基准"吃掉** ——
         * 如果那一口正好发生在静默窗口里，就永久丢了：串口上 MARK 涨了，App 一口都没记。
         */
        val sameSpoon = address == lastAddress
        lastAddress = address
        connectedAddress = address
        connectedName = name.ifBlank { "智味勺" }
        battery = -1
        stale = false
        frames = 0
        rx.clear()
        if (!sameSpoon) {
            lastMark = -1
            lastWeight10 = Int.MIN_VALUE
        }
        lastFrameAt = 0L
        status = Status.CONNECTING
        statusText = "正在连接 $connectedName…"
        emitConnection()

        closeGatt()

        val device: BluetoothDevice = try {
            adapter?.getRemoteDevice(address) ?: run { fail("蓝牙不可用"); return }
        } catch (e: IllegalArgumentException) {
            fail("设备地址无效：$address")
            return
        } catch (e: SecurityException) {
            fail("缺少蓝牙连接权限")
            return
        }

        try {
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(ctx, false, gattCallback)
            }
            Log.i(TAG, "connectGatt($address) 已发起，gatt=${if (gatt != null) "非空" else "null"}")
        } catch (e: SecurityException) {
            fail("缺少蓝牙连接权限")
            return
        }

        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    /** 断开并**不再**自动重连（用户在设备页点的「断开连接」）。 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        wantConnected = false
        main.removeCallbacks(connectTimeout)
        closeGatt()
        status = Status.IDLE
        statusText = "已断开连接"
        connectedName = ""
        connectedAddress = ""
        lastAddress = null
        stale = false
        battery = -1
        frames = 0
        lastMark = -1
        // 断开时清掉待发命令：它们已经没有意义，留着会让重连后突然冒出几条旧命令
        writeQueue.clear()
        writeInFlight = false
        emitConnection()
    }

    /** 断线后按最后已知地址重连（内部用，不受 [wantConnected] 影响之外的控制）。 */
    private fun scheduleReconnect() {
        val address = lastAddress ?: return
        if (!wantConnected || !autoReconnect) return
        main.postDelayed({
            if (wantConnected && status != Status.CONNECTED) {
                statusText = "正在重新连接…"
                emitConnection()
                connect(address, connectedName)
            }
        }, RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        writeChar = null
        try {
            g.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "断开失败", e)
        }
        try {
            g.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 GATT 失败", e)
        }
    }

    private fun fail(message: String) {
        main.removeCallbacks(connectTimeout)
        status = Status.FAILED
        statusText = message
        error = message
        emitConnection()
        closeGatt()
    }

    private fun emitConnection() {
        val state = status
        val name = connectedName
        val message = statusText
        listeners.forEach { it.onSpoonConnection(state, name, message) }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, gattStatus: Int, newState: Int) {
            val state = newState
            val st = gattStatus
            main.post {
                /*
                 * 只处理"当前这一个" GATT 的回调。
                 *
                 * 旧连接被 `closeGatt()` 关掉时，系统回调是**异步**到达的；如果那时用户已经
                 * 开始连另一台（`gatt` 已经指向新对象），旧回调里的 STATUS 会把新连接误判成
                 * "掉线"并把它一起关掉 —— 表现就是"连第二台怎么都连不上"。
                 */
                if (g !== gatt) return@post
                Log.i(TAG, "onConnectionStateChange state=$state status=$st")
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        statusText = "已连接，正在准备数据通道…"
                        emitConnection()
                        try {
                            g.discoverServices()
                        } catch (e: SecurityException) {
                            fail("缺少蓝牙连接权限")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        closeGatt()
                        if (st == BluetoothGatt.GATT_SUCCESS && !wantConnected) {
                            status = Status.IDLE
                            statusText = "已断开连接"
                        } else {
                            status = Status.FAILED
                            statusText = when (st) {
                                BluetoothGatt.GATT_SUCCESS -> "与勺子的连接已断开"
                                8 -> "连接超时（勺子可能已经休眠）"
                                19 -> "勺子主动断开了连接（可能被其他设备占用）"
                                133 -> "连接失败（GATT 133）：请关掉勺子的其他连接后重试"
                                else -> "连接断开（状态码 $st）"
                            }
                        }
                        stale = false
                        emitConnection()
                        // 连上过又掉了、或压根没连上：都按最后已知地址重连（用户主动断开的走 wantConnected=false 不重连）
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, gattStatus: Int) {
            main.post {
                if (g !== gatt) return@post      // 旧连接的回调，丢掉（理由见上面那段注释）
                if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
                    fail("读取勺子服务失败（状态码 $gattStatus）")
                    return@post
                }
                val service = g.getService(SpoonProtocol.SERVICE)
                if (service == null) {
                    fail("这台设备不是智味勺（没有 NUS 服务）")
                    return@post
                }
                val notify = service.getCharacteristic(SpoonProtocol.NOTIFY)
                writeChar = service.getCharacteristic(SpoonProtocol.WRITE)
                if (notify == null) {
                    fail("勺子缺少数据特征值（6E400003）")
                    return@post
                }
                try {
                    g.setCharacteristicNotification(notify, true)
                    val cccd = notify.getDescriptor(SpoonProtocol.CCCD)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            run {
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                g.writeDescriptor(cccd)
                            }
                        }
                    } else {
                        // 没有 CCCD 的极简固件：setCharacteristicNotification 就已经生效
                        markReady()
                    }
                } catch (e: SecurityException) {
                    fail("缺少蓝牙连接权限")
                }
                // 顺便把标准电池服务读出来（没有就算了，状态帧里也会带电量）
                readBatteryLevel(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, gattStatus: Int) {
            main.post {
                if (g !== gatt) return@post
                if (descriptor.uuid == SpoonProtocol.CCCD) {
                    if (gattStatus == BluetoothGatt.GATT_SUCCESS) markReady() else fail("打开数据通道失败（状态码 $gattStatus）")
                }
            }
        }

        /**
         * 一条命令写完了 —— 继续发队列里的下一条（见 [writeQueue] 的说明）。
         *
         * 注意 `onCharacteristicWrite` **只有一个三参数版本**（不像 `onCharacteristicRead`
         * 有带 `value` 的四参数重载，那是 API 33 才加的）—— 用 `javap` 查过 android-36 的
         * `android.jar` 确认。所以这里一个重载就够了；写的是哪个 API
         * （`writeCharacteristic(char, byte[], int)` 还是老的 set-value 写法）都会回调到它。
         */
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            gattStatus: Int,
        ) {
            if (g !== gatt) return
            main.post { onWriteComplete(gattStatus == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            gattStatus: Int,
        ) {
            if (g !== gatt) return
            if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                onBatteryBytes(value)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            gattStatus: Int,
        ) {
            if (g !== gatt) return
            if (gattStatus == BluetoothGatt.GATT_SUCCESS) onBatteryBytes(value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (g !== gatt) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onNotifyBytes(value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== gatt) return
            onNotifyBytes(value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssiValue: Int, gattStatus: Int) {
            if (g !== gatt) return
            if (gattStatus == BluetoothGatt.GATT_SUCCESS) main.post { rssi = rssiValue }
        }
    }

    private fun onBatteryBytes(value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        val level = value[0].toInt() and 0xFF
        if (level in 0..100) {
            main.post {
                battery = level
                /*
                 * 同时记到"这一台设备"上。
                 *
                 * `battery` 只描述"当前连接"，而"连接智味勺"列表里可能有好几台 ——
                 * 用户想在连之前就看到每台剩多少电，所以按地址把值挂到对应那台上，
                 * 这样即使断开/换了另一台，这台读到的值仍然留着。
                 */
                detected.firstOrNull { it.address == connectedAddress }?.battery = level
                listeners.forEach { it.onSpoonBattery(connectedAddress, level) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(g: BluetoothGatt) {
        val batteryService = g.getService(java.util.UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")) ?: return
        val characteristic = batteryService.getCharacteristic(
            java.util.UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        ) ?: return
        try {
            g.readCharacteristic(characteristic)
        } catch (e: SecurityException) {
            Log.w(TAG, "读电量失败", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun markReady() {
        if (status == Status.CONNECTED) return
        main.removeCallbacks(connectTimeout)
        status = Status.CONNECTED
        statusText = "已连接，正在接收勺子数据"
        lastFrameAt = System.currentTimeMillis()
        stale = false
        emitConnection()
        requestRssi()
        // 打个招呼：老固件不回也没关系，只是给我们一个 VER/APP 应答看协议通不通
        send(SpoonProtocol.CMD_PING, silent = true)
        /*
         * 顺带问一次固件版本。
         *
         * 1.1 与 1.2 的**记口方式不一样**（按键触发 vs BodySense 自动判定），
         * 排查"吃了不记口"时第一件要知道的就是勺子里烧的是哪一版 ——
         * 以前只能靠人拿 nRF Connect 手动问，现在连上就自动记进日志（[BleSpoon] 里打成 INFO）。
         * 老固件不认这个命令也没关系：它回一条 `E,CMD,unknown command`，不影响任何逻辑。
         */
        send(SpoonProtocol.CMD_VER, silent = true)
    }

    @SuppressLint("MissingPermission")
    fun requestRssi() {
        try {
            gatt?.readRemoteRssi()
        } catch (e: SecurityException) {
            Log.w(TAG, "读 RSSI 失败", e)
        }
    }

    /* ------------------------------------------------------------ 下发命令 */

    /**
     * 待写的命令队列。
     *
     * **为什么需要它**：下发用的是 `WRITE_TYPE_DEFAULT`（带响应写），而 Android 的 GATT
     * 同一时刻只允许**一个**未完成的写。前一条还没收到 `onCharacteristicWrite` 就发第二条，
     * 第二次 `writeCharacteristic()` 会直接返回失败/被丢弃 —— **命令静默消失**。
     *
     * 这不是理论问题：连上时我们连着发 `PING` 和 `VER?`，实测就只回来了 `A APP,1`，
     * 版本那条被丢了。同理，用户快速点两次「归零」也会丢一条。
     *
     * 所以改成排队：写完成回调里再发下一条（见 [onWriteComplete]）。
     */
    private val writeQueue = ArrayDeque<Pair<String, Boolean>>()
    private var writeInFlight = false

    /** 正在写的那条命令（只用于日志）。 */
    private var writingCommand = ""

    /**
     * 给勺子发一条命令（自动补 `\n`）。
     *
     * @param silent true = 失败不提示（握手用），false = 失败时 toast（用户点的动作）
     */
    @SuppressLint("MissingPermission")
    fun send(command: String, silent: Boolean = false) {
        val g = gatt
        val characteristic = writeChar
        if (g == null || characteristic == null || status != Status.CONNECTED) {
            if (!silent) say("勺子未连接，命令没发出去")
            return
        }

        writeQueue.addLast(command to silent)
        drainWriteQueue()
    }

    /**
     * 把队列里的命令一条条发出去。
     *
     * 只在"当前没有正在写的"时发第一条，剩下的等 [onWriteComplete] 来推。
     * 队列长度设了上限：正常情况最多排 2~3 条；真到了几十条说明链路卡住了，
     * 与其无限堆积（用户以为命令都发出去了），不如丢掉最老的并说出来。
     */
    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val characteristic = writeChar ?: return

        while (writeQueue.isNotEmpty()) {
            if (writeQueue.size > MAX_COMMAND_QUEUE) {
                val dropped = writeQueue.removeFirst()
                Log.w(TAG, "命令队列过长，丢弃最老的一条: ${dropped.first}")
            }
            val (command, silent) = writeQueue.removeFirst()
            val payload = (command.trimEnd('\n') + "\n").toByteArray(Charsets.UTF_8)

            val started = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(
                        characteristic,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        characteristic.value = payload
                        g.writeCharacteristic(characteristic)
                    }
                }
            } catch (e: SecurityException) {
                if (!silent) say("缺少蓝牙权限，命令没发出去")
                return
            }

            if (started) {
                writeInFlight = true
                writingCommand = command.trim()
                return
            }
            if (!silent) say("命令发送失败，请重试")
        }
    }

    /** 一条命令写完了（GATT 回调），继续发队列里的下一条。 */
    private fun onWriteComplete(ok: Boolean) {
        writeInFlight = false
        if (!ok) Log.w(TAG, "命令写入失败: $writingCommand")
        drainWriteQueue()
    }

    /** 「点击归零重量」：下发 ZERO（固件侧会 `tare()`）。 */
    fun zero() = send(SpoonProtocol.CMD_ZERO)

    /* -------------------------------------------------------------- 解析 */

    private fun onNotifyBytes(value: ByteArray) {
        // String(value) 用 UTF-8 解；协议是纯 ASCII，所以半包处不会切坏多字节字符
        val chunk = String(value, Charsets.UTF_8)
        main.post {
            rx.feed(chunk).forEach { line -> handleLine(line) }
        }
    }

    private fun handleLine(line: String) {
        val event = SpoonProtocol.parseLine(line) ?: return
        when (event.kind) {
            'S' -> {
                /*
                 * 正常的 `S` 帧只有 4~5 个字段。**多出很多字段 = 两帧粘成了一行**
                 * （BLE 数据被挤在一起 / 丢了换行），这种帧里的数字会粘成 5100 这样的大数。
                 * 现场排查时最有价值的就是原始行长什么样，所以这里原样打出来。
                 */
                if (event.fields.size > 6) {
                    Log.w(TAG, "可疑状态帧（${event.fields.size} 个字段，疑似粘帧）: ${line.take(120)}")
                }
                val s = SpoonProtocol.parseStatus(event.fields) ?: return
                applyStatus(s)
            }
            'B' -> {
                val rawMark = event.fields.getOrNull(0)?.toIntOrNull() ?: (lastMark + 1)
                val fromFrame = event.fields.getOrNull(1)?.toIntOrNull() ?: 0
                /*
                 * `B` 帧里的 mark 也要过量程：现场出现过 `B,5100,…` 这种帧
                 * （BLE 数据被挤在一起时数字粘成一个大数）。垃圾值一旦进了基准，
                 * 后面所有正常的 mark 都会被判成"已处理过"，就永远不再记口了。
                 */
                if (rawMark < 0 || rawMark > SpoonProtocol.MAX_MARK) {
                    Log.w(TAG, "丢弃可疑的 B 帧: mark=$rawMark weight10=$fromFrame（超量程）")
                    return
                }
                Log.i(TAG, "收到 B 事件帧: mark=$rawMark weight10=$fromFrame (lastMark=$lastMark peak=$peakWeight10)")
                // 真正"这一口多少克"的兜底逻辑在 emitBite 里（两条上报路径共用）
                emitBite(fromFrame, rawMark)
            }
            'A' -> {
                listeners.forEach { it.onSpoonEvent(event) }
                // 应答里如果带版本号，直接提示一下，便于现场确认固件对不对
                if (event.fields.firstOrNull()?.equals("VER", true) == true) {
                    val version = event.fields.getOrNull(1) ?: ""
                    if (version.isNotBlank()) say("勺子固件版本：$version")
                }
            }
            'E' -> {
                val text = event.fields.drop(1).joinToString(",")
                say("勺子报告错误：${text.ifBlank { event.fields.firstOrNull() ?: "未知" }}")
            }
        }
    }

    private fun applyStatus(s: SpoonProtocol.Status) {
        lastFrameAt = System.currentTimeMillis()
        if (stale) {
            stale = false
            statusText = "已连接，正在接收勺子数据"
            emitConnection()
        }
        // 记下"数据流刚刚恢复"：mark 若在这之后一跳多口，说明静默期间漏了口（见下面 mark 那段）
        recentStale = System.currentTimeMillis() - staleEndedAt < STALE_RECENT_MS
        frames++
        if (s.battery in 0..100) battery = s.battery

        /*
         * 每 2 秒打一条状态帧摘要（**只在 debug 构建**）。
         *
         * 为什么需要：自动记口是**在固件里**根据体感判定发生的，手机这一侧只看得到结果
         * （mark 变没变）。现场要回答"到底判没判出来"，就必须能看到 `body` 与 `mark`
         * 的连续变化 —— 有了这行日志，"嘴接触时 body 有没有越过基准""之后 mark 有没有 +1"
         * 一眼就能看出来，不用再去接串口（串口一接，App 就得断开）。
         *
         * 2 秒一条是刻意的：100ms 一条会刷满 logcat，反而看不清趋势。
         */
        if (BuildConfig.DEBUG) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastStatusLogAt >= STATUS_LOG_INTERVAL_MS) {
                lastStatusLogAt = nowMs
                Log.i(
                    TAG,
                    "状态帧: 重量=${s.weight10 / 10.0}g 体感=${s.body} " +
                        "稳定=${if (s.stable) 1 else 0} mark=${s.mark} 电量=${s.battery}",
                )
            }
        }

        // seq 语义：内容真的变了才 +1（与服务器模拟器一致，界面可据此判断"有新数据"）
        if (s.weight10 != lastWeight10 || s.stable != lastStable) {
            seq++
            lastWeight10 = s.weight10
            lastStable = s.stable
        }
        /*
         * 记住"最近一次非零读数"。
         *
         * 兜底用的历史值。两种情况都会用到它：
         * - 老固件（≤1.1）：报"一口"的时刻往往在读数已经回到 0 之后（按键时勺子早空了）；
         * - 新固件（≥1.2）：`B` 帧里带的是"吃前 − 吃后"的净重，这个兜底只在**没带重量字段**
         *   或那一帧丢了的时候才生效。
         * 无论哪种，直接取当前读数都可能拿到 0，所以留一个非零的历史值垫底。
         */
        if (s.weight10 > 0) peakWeight10 = s.weight10

        /*
         * 口数的**兜底**路径：老固件只发状态帧（没有 `B` 事件帧），靠 `mark` 的增量也能记口。
         *
         * 重量一律传 0（= 让 [emitBite] 用"最近一次非零读数"），不要传当前读数：
         * 这条路径本来就是"读数已经掉回 0 之后才发现 mark 涨了"。
         * 已经收到过 `B` 帧的那一口由 [emitBite] 去重，不会记两遍。
         */
        if (lastMark < 0) {
            lastMark = s.mark          // 首帧只对齐基准，不补记历史
            Log.i(TAG, "对齐 mark 基准: ${s.mark}（这一帧之前的口不补记）")
        } else if (s.mark > lastMark) {
            val jump = s.mark - lastMark
            /*
             * 一跳跨了多口：几乎只可能是"我们刚才有静默窗口"（数据断了 3 秒以上），
             * 这期间吃下的几口已经无从考证（B 帧丢了、重量也没采到）。
             * 记下来而不是静默吞掉 —— 用户至少能从日志里看出"刚才漏了口"。
             */
            if (jump > 1 && recentStale) {
                Log.w(
                    TAG,
                    "静默期间的 ${jump - 1} 口无法补记（mark $lastMark -> ${s.mark}，" +
                        "B 帧与重量都随数据流一起丢了）",
                )
            }
            Log.i(TAG, "状态帧里 mark 增长: $lastMark -> ${s.mark}（走兜底记口路径）")
            lastMark = s.mark
            emitBite(0, s.mark)
        }

        listeners.forEach { it.onSpoonStatus(s, seq) }
    }

    /**
     * 报一口给上层。**这是两条上报路径（`B` 事件帧、`mark` 增量）唯一的出口。**
     *
     * @param weight10 固件在这一帧里给的重量（×10）；`<= 0` 表示"没给或给的是 0"，
     *   此时退回到 [peakWeight10]（最近一次非零读数）—— 真机上这一口往往是在勺子已经空了
     *   之后才报上来的，用当前读数会得到 0。
     */
    private fun emitBite(weight10: Int, mark: Int) {
        if (mark <= lastMark) {
            /*
             * 去重要留一条**活路**。
             *
             * 只写 `mark in 0..lastMark -> return` 的话，一旦 `lastMark` 被一个异常大的值
             * （现场见过 5100）顶住，之后所有正常的 7、8、9… 全部落进这段直接返回 ——
             * 表现就是"串口 MARK 一直在涨，App 一口都不记"，而且**永远不会自愈**。
             *
             * 所以这里区分两种"比基准小"：
             * - 只差一点点（<= 阈值）：正常的重复帧 / 乱序帧，忽略；
             * - 差得离谱：说明是勺子重启（mark 归零）或基准本身是脏数据，
             *   那就把基准重置到当前值重新同步（丢这一口，换回后面所有口）。
             */
            if (lastMark - mark <= MARK_RESYNC_GAP) {
                Log.i(TAG, "口数去重: mark=$mark 已处理过（lastMark=$lastMark），忽略")
                return
            }
            /*
             * 差得离谱：两种可能都能自愈，日志里把**两种**都写清楚 ——
             * 否则现场看到"判为勺子重启"会去查固件，而实际上只是基准里还留着上一轮的脏值。
             */
            Log.w(
                TAG,
                "mark 基准从 $lastMark 重置为 $mark（差 ${lastMark - mark}）：" +
                    "要么是勺子重启（mark 归零），要么是基准里留了上一轮的脏值",
            )
        }
        lastMark = maxOf(lastMark, mark)
        val grams = when {
            weight10 > 0 -> weight10 / 10.0
            peakWeight10 > 0 -> peakWeight10 / 10.0
            else -> 0.0
        }
        Log.i(
            TAG,
            "报出一口: mark=$mark 重量=${"%.1f".format(grams)}g" +
                "（B 帧给 ${weight10 / 10.0}g，兜底峰值 ${peakWeight10 / 10.0}g）",
        )
        listeners.forEach { it.onSpoonBite(grams, mark) }
    }

    /**
     * "比基准小"超过这个幅度就认为基准是脏的（而不是重复帧），重置基准。
     *
     * 取 100 的理由：正常重复/乱序只会差 0~1，而脏数据（5100）与真实 mark（个位数）
     * 差着三个数量级，中间留足余量；同时 100 也大于"用户连按一百下"这种极端情况，
     * 不会把正常的口误判成重启。
     */
    private const val MARK_RESYNC_GAP = 100

    /**
     * 下发命令队列的长度上限。
     *
     * 正常最多排 2~3 条（握手两条 + 用户点一次归零）。真到几十条说明链路卡住了，
     * 与其无限堆积（用户以为命令都发出去了），不如丢掉最老的并记日志。
     */
    private const val MAX_COMMAND_QUEUE = 8

    /** "状态帧摘要"日志的节流间隔（debug 构建；见 [applyStatus]）。 */
    private const val STATUS_LOG_INTERVAL_MS = 2_000L
}