package com.smartspoon.l2

import org.json.JSONObject

/**
 * 餐次在**服务器侧**的动作 —— 阶段 2 抽出的第一批会话逻辑。
 *
 * 它们只依赖 [State] 与 [Api]，不碰任何 Activity（不需要 runOnUiThread、不需要 Context），
 * 所以「用餐中 / 用餐结果」以后拆成独立 Activity 时可以直接共用同一份，
 * 不会出现两个 Activity 各写一半服务器状态的情况。
 *
 * [MainActivity] 里原来那三个同名私有方法现在只是转发到这里，行为一字未改。
 */
object MealSessionActions {

    /** 告诉服务器「开始记录」，并清空上一餐的口。 */
    fun startRecording() {
        Thread {
            Api.postDevice(State.server, "/api/device/clear", JSONObject())
            Api.postDevice(State.server, "/api/device/state", JSONObject().apply {
                put("recording", true)
                put("weight", 0)
            })
        }.start()
    }

    /** 告诉服务器「停止记录」（不清口）。 */
    fun stopRecording() {
        Thread {
            Api.postDevice(State.server, "/api/device/state", JSONObject().apply {
                put("recording", false)
            })
        }.start()
    }

    /** 恢复记录：只把 recording 置回 true，不动已经累计的口。 */
    fun resumeRecording() {
        Thread {
            Api.postDevice(State.server, "/api/device/state", JSONObject().apply {
                put("recording", true)
            })
        }.start()
    }
}