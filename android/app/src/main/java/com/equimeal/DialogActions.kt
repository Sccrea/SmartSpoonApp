package com.equimeal

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

    /*
     * 真机蓝牙（BLE）的三个动作。
     *
     * 它们要求宿主持有 [BlePermissions]（权限注册绑定 Activity 生命周期），所以实现放在
     * 各个 Activity 上而不是 [SpoonLink] 里：宿主要权限 → 回调里调 [SpoonLink]。
     */
    /** 「附近的智味勺」：申请权限后开始扫描。 */
    fun scanSpoons()
    /** 连接扫描到的一台勺子。 */
    fun connectSpoon(address: String, name: String)
    /** 断开当前真机连接。 */
    fun disconnectSpoon()
    /**
     * 只申请蓝牙权限（不顺手做别的）。
     *
     * 权限还没给的时候，弹窗要给用户一个明确的按钮，而不是点了「重新扫描」什么都没发生 ——
     * 那个按钮走这里。申请必须由 Activity 发起，理由见 [BlePermissions] 的类注释。
     */
    fun grantBlePermission()

    /**
     * 「权限 + 蓝牙开关」都就绪之后再做某事。
     *
     * 给设备行那个「连接」按钮用的：**系统弹窗只能由当前 Activity 发起**，
     * 而各页面的 `BlePermissions` 是饿汉式建好的（只有那时注册才合法）。
     * 所以"要权限/开蓝牙"由页面提供，业务层（`BleSpoon`）只提出请求。
     */
    fun bleReady(onReady: () -> Unit)

    /** 设备管理里点一行 → 改这台勺子的名字。 */
    fun showRenameDevice(deviceId: String)
    /** 改名弹窗的「保存」。返回要提示的文字。 */
    fun renameDevice(deviceId: String, name: String): String

    /* 用餐提醒与餐次 */
    fun confirmRemindAndStart()
    fun startMeal()
    fun currentBiteList(): List<Bite>
    fun mealBites(mealId: Long): List<Bite>

    /* 结果与记录 */
    fun saveResultField(field: String, value: String)
    fun deleteMealConfirmed(mealId: Long)

    /**
     * 「给这一餐起个名字」弹窗的保存（自定义菜单的用餐结束时弹）。
     *
     * 名字为空时回落默认名「自定义菜单」 —— 清空输入框点保存不该产生一条没有名字的记录。
     *
     * 这里有**默认实现**，因为它只动 [MealSession] 与 [State.dialog]（纯状态逻辑），
     * 不需要 Activity 的任何能力。做成抽象方法的话，七个设置子页都得写一个空实现 ——
     * 而它们的弹窗里根本不会出现这个对话框。
     */
    fun renameMeal(name: String) {
        closeOverlay()
        MealSession.renameMeal(name)
    }

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