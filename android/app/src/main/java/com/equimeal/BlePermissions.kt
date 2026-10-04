package com.equimeal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 蓝牙权限的申请入口 —— 与 [PhotoRecognizer] 同样的写法（Activity Result API），
 * 同样要求宿主**饿汉式**持有：
 *
 * ```kotlin
 * private val ble = BlePermissions(this)      // ✅ 字段初始化
 * private val ble by lazy { BlePermissions(this) }   // ❌ 一点「扫描」就崩
 * ```
 *
 * `registerForActivityResult` 只允许在 Activity STARTED 之前注册，`by lazy` 会把注册
 * 推到第一次调用时（那时已经是 RESUMED），直接抛 IllegalStateException。
 *
 * ## 为什么 12 与 12 以下要分开
 *
 * | 系统 | 扫描要的运行时权限 | 备注 |
 * | --- | --- | --- |
 * | Android 12+（API 31） | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` | `neverForLocation` 之后**不再需要定位**，也不会出现"扫描附近的设备"那种定位告警 |
 * | Android 11-（API 24~30） | `ACCESS_FINE_LOCATION` | 老模型：BLE 扫描被归类为定位用途，没有定位权限扫描直接返回空 |
 *
 * 这套分支只在 [State.bleRuntimePermissions] 里写了一次，界面与 [BleSpoon] 都调用它，
 * 不会出现"这里问了定位、那里问了蓝牙"的混乱。
 */
class BlePermissions(private val activity: ComponentActivity) {

    private companion object {
        const val TAG = "BlePermissions"

        /** 用户点「允许」之后，等蓝牙真的变成开着的上限（协议栈异步起动）。 */
        const val ENABLE_WAIT_MS = 3000L
    }

    private var onResult: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val callback = onResult
        onResult = null
        val granted = result.values.all { it }
        if (!granted) {
            State.bleHint = "未授予蓝牙权限，无法扫描/连接智味勺"
            AppCore.toast(State.bleHint!!)
        }
        callback?.invoke(granted)
    }

    /**
     * 请用户打开蓝牙用的是**系统自己的弹窗**（`ACTION_REQUEST_ENABLE`）。
     *
     * 以前这里写的是 `activity.startActivityForResult(...)` + 在宿主 `onActivityResult` 里
     * 判断 `REQ_ENABLE_BLUETOOTH` —— 但全项目**没有任何地方实现过那个分支**，
     * 也就是说那段代码从来没生效过：用户看到的是"蓝牙未开启"的提示，
     * 而系统弹窗压根没弹出来。改用 Activity Result API 之后不再需要宿主的 onActivityResult，
     * 谁都可以安全地调。
     */
    private val enableBluetoothLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 不看 resultCode：有的 ROM 在蓝牙本来就是开的时候回 RESULT_CANCELED，
        // 真相只有一个 —— 直接问适配器现在开着没有
        val done = onEnabled
        onEnabled = null
        Log.i(TAG, "系统打开蓝牙弹窗已返回，resultCode=${it.resultCode}，adapter.isEnabled=${SpoonLink.isBluetoothOn()}")
        /*
         * **不能立刻只看一眼**：用户点「允许」之后，蓝牙协议栈是在后台异步起来的，
         * 这个回调（以及 Activity 的 onResume）往往比"适配器真的变成 enabled"更早。
         * 只看一眼就会读到 false，于是"用户明明同意了、扫描却永远不开始" ——
         * 现象与"应用没有请求打开蓝牙"几乎一样，非常难查。所以这里等它一下。
         */
        awaitBluetoothOn { enabled -> done?.invoke(enabled) }
    }

    private var onEnabled: ((Boolean) -> Unit)? = null

    /**
     * 等蓝牙真的变成开着（最多 [ENABLE_WAIT_MS] 毫秒）。
     *
     * 用轮询而不是广播：这里只需要一个"最终开没开"的结论，
     * 广播还要处理注册/注销生命周期，反而更容易漏。
     */
    private fun awaitBluetoothOn(onDone: (Boolean) -> Unit) {
        if (SpoonLink.isBluetoothOn()) {
            Log.i(TAG, "蓝牙已打开，继续")
            onDone(true)
            return
        }
        val deadline = System.currentTimeMillis() + ENABLE_WAIT_MS
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (SpoonLink.isBluetoothOn()) {
                    Log.i(TAG, "蓝牙已打开（等待后确认），继续")
                    onDone(true)
                } else if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 200)
                } else {
                    Log.w(TAG, "等 ${ENABLE_WAIT_MS}ms 后蓝牙仍未打开，放弃")
                    State.bleHint = "蓝牙仍未打开：请打开手机的蓝牙开关后重试"
                    AppCore.toast(State.bleHint!!)
                    onDone(false)
                }
            }
        }
        handler.postDelayed(poll, 200)
    }

    /**
     * 确保蓝牙权限可用；已经有权限就**同步**回调 true。
     *
     * @param onReady 权限就绪（或本来就有）之后要做的事，例如开始扫描 / 发起连接。
     */
    fun ensure(onReady: () -> Unit) {
        val context: Context = activity
        if (State.hasBlePermissions(context)) {
            Log.i(TAG, "权限已具备（permissions=${State.bleRuntimePermissions().size} 项）")
            onReady()
            return
        }
        if (!declaresBluetoothPermissions()) {
            State.bleHint = "系统没有声明蓝牙权限：请检查 AndroidManifest.xml"
            AppCore.toast(State.bleHint!!)
            return
        }
        Log.i(TAG, "申请蓝牙权限：${State.bleRuntimePermissions().joinToString { it.substringAfterLast('.') }}")
        onResult = { granted -> if (granted) onReady() }
        launcher.launch(State.bleRuntimePermissions())
    }

    /**
     * 确保"权限 + 蓝牙开关"都就绪，然后执行 [onReady]。
     *
     * 与 [ensure] 的区别就是多管了**蓝牙开关**：没开就用系统弹窗请用户打开（[requestEnableBluetooth]），
     * 用户同意之后再继续。扫描与连接都走这个入口 —— 否则用户在"没开蓝牙"的状态下点「扫描」，
     * 只会看到一句"请先打开手机蓝牙"，还得自己退出去拉通知栏。
     */
    fun ensureReady(onReady: () -> Unit) {
        Log.i(TAG, "ensureReady 进入（蓝牙开着=${SpoonLink.isBluetoothOn()}，有适配器=${SpoonLink.hasBluetooth()}）")
        ensure {
            if (SpoonLink.isBluetoothOn()) {
                onReady()
            } else {
                requestEnableBluetooth { enabled ->
                    if (enabled) onReady()
                }
            }
        }
    }

    /** 用系统弹窗请求打开蓝牙；[onDone] 收到"最终开着没有"。 */
    private fun requestEnableBluetooth(onDone: (Boolean) -> Unit) {
        if (!SpoonLink.hasBluetooth()) {
            AppCore.toast("这台设备没有蓝牙，无法连接智味勺")
            onDone(false)
            return
        }
        Log.i(TAG, "发起系统「请求打开蓝牙」弹窗")
        State.bleHint = "正在请求打开蓝牙…"
        onEnabled = onDone
        try {
            enableBluetoothLauncher.launch(
                Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "请求打开蓝牙失败：$e")
            onEnabled = null
            State.bleHint = "请手动在系统设置里打开蓝牙"
            AppCore.toast(State.bleHint!!)
            onDone(false)
        }
    }

    private fun declaresBluetoothPermissions(): Boolean = try {
        val info = activity.packageManager.getPackageInfo(
            activity.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions?.any {
            it == Manifest.permission.BLUETOOTH_SCAN ||
                it == Manifest.permission.BLUETOOTH_CONNECT ||
                it == Manifest.permission.ACCESS_FINE_LOCATION
        } == true
    } catch (e: Exception) {
        true
    }

    /** 用户明确拒绝过（"不再询问"）时，只能把他送到系统设置页。 */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", activity.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            AppCore.toast("无法打开系统设置")
        }
    }
}
