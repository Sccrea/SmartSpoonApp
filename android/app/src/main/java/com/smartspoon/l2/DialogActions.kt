package com.smartspoon.l2

/**
 * 弹窗宿主需要提供的能力 —— 阶段 2 第 3 步：把 `AppDialogs` 从 `MainActivity` 上解绑。
 *
 * 原先 11 个弹窗组件的参数类型直接写着 `MainActivity`，于是**任何别的 Activity 都没法挂载
 * 弹窗**（这正是「设置子页 / 统计数据」当初不得不各写一份弹窗的原因）。
 * 现在弹窗只依赖这个接口，里面全是"写 [State] / 触发一次动作"的方法，
 * 与界面宿主无关：[MainActivity] 用一个委托对象实现它（自身那些方法一行未改），
 * 以后拆出去的 Activity 也可以各自实现一份。
 */
interface DialogActions {
    fun closeOverlay()

    /* 连接与设备 */
    fun showConnectDialog(picking: Boolean)
    fun selectConnectedDevice(deviceId: String)
    fun zeroSpoonWeight()

    /* 用餐提醒与餐次 */
    fun confirmRemindAndStart()
    fun startMeal()
    fun currentBiteList(): List<Bite>
    fun mealBites(mealId: Long): List<Bite>

    /* 结果与记录 */
    fun saveResultField(field: String, value: String)
    fun deleteMealConfirmed(mealId: Long)

    /* 菜品 */
    fun saveDishFromEditor(foodId: String?, name: String, category: String, density: Double)
    fun deleteDishConfirmed(foodId: String)
    fun addRecognizedToMenu(foodId: String)
    fun saveDishToServer(name: String, calorie: Double)

    /* 服务器 */
    fun saveServerAddress(text: String)
    fun goOfflineDemo()

    /* 相机与识别 */
    fun takePhoto()
    fun pickImage()
}