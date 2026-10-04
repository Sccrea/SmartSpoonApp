package com.equimeal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 一个**只做一件事**的透明 Activity：替调用方申请蓝牙权限。
 *
 * ## 为什么需要它
 *
 * Android 的运行时权限必须由一个 Activity 发起，而 `registerForActivityResult` 只能在
 * Activity **STARTED 之前**注册。于是"在弹窗里点一下扫描"这种场景就卡住了：
 *
 * - 「连接智味勺」弹窗是普通 Compose 弹窗，**不是 Activity**，它没法自己申请权限；
 * - [AppCore.showConnectDialog] 也不该持有 Activity（它是应用级的，还要求 Activity 已 STARTED）。
 *
 * 这个类就是那个缺口：任何地方（弹窗、非 Activity 上下文）都可以用 [ensure] 起一个它，
 * 它申请完权限、回调结果、自己 `finish()`。Activity 的生命周期问题被关在这个小类里，
 * 调用方只看到一个 `(Boolean) -> Unit` —— 也就是 Android 官方推荐的
 * "permission trampoline" 写法。
 *
 * 它在界面上**不可见**（透明主题、无动画），用户只会看到系统的权限弹窗。
 * 刻意**不加** `noHistory`：加了的话权限弹窗一出现它就被销毁，结果没有人接，
 * 表现是"点了允许却什么都没发生"。
 */
class BlePermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 现场排查用：真机上"点了允许却扫不动"时，这条日志能直接说明权限到底给没给
        android.util.Log.i(
            "BlePermission",
            "权限结果: " + result.entries.joinToString(", ") { "${it.key.substringAfterLast('.')}=${it.value}" },
        )
        finishWith(result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (State.hasBlePermissions(this)) {
            android.util.Log.i("BlePermission", "权限已就绪，直接放行")
            finishWith(true)
        } else {
            val needed = State.bleRuntimePermissions()
            android.util.Log.i("BlePermission", "申请权限: ${needed.joinToString(", ") { it.substringAfterLast('.') }}")
            launcher.launch(needed)
        }
    }

    private fun finishWith(granted: Boolean) {
        if (!granted) {
            State.bleHint = "未授予蓝牙权限，无法扫描/连接智味勺"
            AppCore.toast(State.bleHint!!)
        }
        val callback = pending
        pending = null
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        /*
         * 极端情况下（进程被系统回收重建）回调可能已经丢了，那就什么都没发生 ——
         * 用户会停在原来的页面上，再点一次「重新扫描」即可。这里不做自动重试：
         * 权限弹窗被拒绝后自动再弹一次是明确的反模式（有些 ROM 会直接静默拒绝）。
         */
        callback?.invoke(granted)
    }

    companion object {
        /**
         * 最近一次请求的回调。
         *
         * 用进程级静态字段是**刻意**的：这个 Activity 是透明的、随时可能被系统回收重建，
         * 把回调存在实例里会跟着一起丢。同一时刻只会有一个权限弹窗（系统是模态的），
         * 所以不存在并发覆盖。
         */
        private var pending: ((Boolean) -> Unit)? = null

        /**
         * 确保蓝牙权限可用：已有就**同步**回调；没有就跳这个 Activity 去申请。
         *
         * @param onReady 权限就绪（或本来就有）之后要做的事，例如开始扫描。
         */
        fun ensure(context: Context, onReady: () -> Unit) {
            if (State.hasBlePermissions(context)) {
                onReady()
                return
            }
            pending = { granted -> if (granted) onReady() }
            try {
                context.startActivity(
                    Intent(context, BlePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                pending = null
                State.bleHint = "无法打开权限请求：${e.message}"
                AppCore.toast(State.bleHint!!)
            }
        }
    }
}
