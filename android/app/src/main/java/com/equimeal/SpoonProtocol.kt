package com.equimeal

import java.util.UUID

/**
 * 智味勺 BLE 协议 —— **App 与勺子固件之间唯一的那份约定**。
 *
 * 传输层用的是 Nordic 官方的 **BLE UART / NUS**（Nordic UART Service）：
 * 勺子固件（`C:\Users\Administrator\Desktop\t8\t8.ino`，Adafruit nRF52840 板级包）
 * 用 `BLEUart bleuart;` 起的就是这套服务，UUID 是 Nordic 固定分配的，不需要自己编：
 *
 * | 角色 | UUID | 方向 |
 * | --- | --- | --- |
 * | 服务 NUS | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | — |
 * | 勺子 → 手机（Notify） | `6E400003-…` | 状态帧与事件帧 |
 * | 手机 → 勺子（Write） | `6E400002-…` | 命令 |
 *
 * ## 为什么要先有一条文本协议
 *
 * 固件那边是「一帧一行、逗号分隔的 ASCII」，理由和一个串口调试助手能看懂它一样：
 * 现场排障时（`nRF Connect` 直接看 Notify、`Serial` 看同一批数据）不需要任何翻译工具，
 * 出问题时人和程序看到的是同一种东西。字段顺序固定，末尾追加新字段不破坏老版本解析。
 *
 * ### 上行（勺子 → 手机，每条以 `\n` 结尾，固件每 100ms 发一条状态帧）
 *
 * ```
 * S,<weight10>,<body>,<stable>,<mark>[,<battery>]
 * ```
 *
 * | 字段 | 含义 | 单位 / 取值 |
 * | --- | --- | --- |
 * | `S` | 帧类型 = 状态帧 | — |
 * | `weight10` | 勺中读数 ×10 | int，`123` = 12.3 g |
 * | `body` | 人体/握持传感（12 位 ADC 均值） | 0~4095 |
 * | `stable` | 姿态是否稳定 | 1 / 0（LSM6DS3 加速度≈1g 且陀螺 < 15dps） |
 * | `mark` | 固件侧的累计计数（开机清零） | int，**单调递增** |
 * | `battery` | 电量百分比，可省略 | 0~100 |
 *
 * ```
 * B,<mark>[,<weight10>]      // 勺子上的「标记」键被按下（一口）
 * A,<k>,<v>[,<k>,<v>…]       // 应答/握手：APP=1, VER=2, ZERO=1, MARK=n
 * E,<code>,<text>            // 错误
 * ```
 *
 * #### `mark` 的语义（很重要）
 *
 * 固件把「标记键按下次数」和「口数」当成**同一个单调计数器**：状态帧里的 `mark`
 * 就是它。手机侧因此可以两条路都走通：
 *
 * 1. 固件主动推 `B` 事件帧（新固件）；
 * 2. 手机监听 `mark` 的**增量**（老固件只发状态帧也能自动记口）。
 *
 * 两条路都收敛到 [SpoonLink.Listener.onBite] 一次，重复计数由 [SpoonLink] 内部的
 * 「已推送的最大 mark」挡住，所以同时支持也不会记两口。
 *
 * ### 下行（手机 → 勺子，ASCII 命令，建议 `\n` 结尾）
 *
 * | 命令 | 作用 | 应答 |
 * | --- | --- | --- |
 * | `ZERO` | 勺中重量归零（重新去皮） | `A,ZERO,1` |
 * | `TARE` | 同 `ZERO`（别名） | `A,ZERO,1` |
 * | `MODE <0-2>` | 切换固件工作模式 | `A,MODE,<n>` |
 * | `PING` | 探活 | `A,APP,1` |
 * | `VER?` | 读固件版本 | `A,VER,<文本>` |
 *
 * 归零是**手机与勺子各做一次**：手机侧把界面读数清 0（立刻反馈），同时把 `ZERO`
 * 下发给勺子让它重新 `tare()`（下次读数就是真 0）。只做手机侧的话，下一个 100ms
 * 状态帧就会把旧读数顶回来 —— 这正是"点了归零却弹回原值"的经典原因。
 */
object SpoonProtocol {

    /** Nordic UART Service。 */
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** 勺子 → 手机 的通知特征值（Notify）。 */
    val NOTIFY: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** 手机 → 勺子 的写特征值（Write / Write without response）。 */
    val WRITE: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** 客户端特征值配置描述符（打开 Notify 用的那个 CCCD）。 */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /**
     * 广播名。固件里是 `Bluefruit.setName("SmartSpoon")`。
     *
     * 名字只作为**辅助**筛选条件：[SpoonLink] 优先按服务 UUID 过滤，
     * 名字对不上（比如烧了别的固件但仍是 NUS）也照样连得上。
     */
    const val DEVICE_NAME = "SmartSpoon"

    /** 名字里出现这些词也认为是勺子（现场排障时改名的情况）。 */
    val NAME_HINTS = listOf("smartspoon", "spoon", "勺子", "智味勺")

    /* ------------------------------------------------------------- 命令 */

    const val CMD_ZERO = "ZERO"
    const val CMD_TARE = "TARE"
    const val CMD_PING = "PING"
    const val CMD_VER = "VER?"

    fun cmdMode(mode: Int) = "MODE $mode"

    /* ------------------------------------------------------------- 解析 */

    /**
     * 一帧状态。
     *
     * @param weight10 原始值（克 ×10）。界面用 [weightGrams]。
     * @param body 握持传感原始值（0~4095），只作诊断/展示用。
     * @param stable IMU 判定的"端稳了"。
     * @param mark 固件累计计数（单调递增）。
     * @param battery 电量百分比；`-1` = 固件没报（老固件）。
     */
    data class Status(
        val weight10: Int,
        val body: Int,
        val stable: Boolean,
        val mark: Int,
        val battery: Int = -1,
    ) {
        val weightGrams: Double get() = weight10 / 10.0
    }

    /** 一帧事件（`B` 标记键 / `A` 应答）。 */
    data class Event(val kind: Char, val fields: List<String>)

    /**
     * 解析一行（**不含**换行符）。
     *
     * 返回 null 表示这一行不是本协议的内容（例如固件启动时打印的日志跑到了 Notify 上）：
     * 直接忽略，不报错 —— 现场排障时经常混着人看的日志。
     *
     * ## 为什么允许帧头前面有垃圾
     *
     * 正常情况下一行就是干干净净的 `S,...`。但真机上出现过"上一段数据没清干净、
     * 于是半个旧帧粘在新帧前面"的情况（换设备、`clear()` 与 Notify 抢时序）。
     * 那种行如果整行判废，后面那个**完好的帧**就一起丢了 —— 表现是"数据流突然断了"，
     * 而现场看到的是勺子明明还在发。
     *
     * 所以这里从每一个合法的帧头（`S,` / `B,` / `A,` / `E,`）都试一次，取第一个能解析的。
     * 干净的行只在第 0 个位置试一次，不增加任何实际开销。
     */
    fun parseLine(line: String): Event? {
        val text = line.trim()
        if (text.isEmpty()) return null

        var start = 0
        while (start < text.length) {
            val candidate = text.indexOfFrameHead(start)
            if (candidate < 0) return null
            val parts = text.substring(candidate).split(',')
            val head = parts[0]
            if (head.length == 1 && head[0] in "SBAE") {
                return Event(head[0], parts.drop(1).map { it.trim() })
            }
            start = candidate + 1
        }
        return null
    }

    /** 从 [from] 开始找第一个"帧头 + 逗号"的位置（`S,` / `B,` / `A,` / `E,`）；找不到返回 -1。 */
    private fun String.indexOfFrameHead(from: Int): Int {
        var i = from
        while (i + 1 < length) {
            val c = this[i]
            if ((c == 'S' || c == 'B' || c == 'A' || c == 'E') && this[i + 1] == ',') return i
            i++
        }
        return -1
    }

    /**
     * 把状态帧的字段解析成 [Status]。
     *
     * 刻意容忍缺字段（老固件只有前 5 个），但**前 4 个字段错了就返回 null**：
     * 宁可少一帧，也不要把一次解析异常变成界面上的一个假读数。
     *
     * 另外对每个字段做了**量程检查**。这不是洁癖：现场真的出现过 `mark` 解析成
     * **5100 / 9100** 这种值的帧（BLE 数据被挤在一起时，数字会粘成一个大数）。
     * 一个这样的垃圾值进了"口数去重基准"，就会把门槛顶到天上 ——
     * 之后所有正常的 mark（7、8、9…）全被判成"已处理过"，表现为**按键永远不记口**。
     */
    fun parseStatus(fields: List<String>): Status? {
        if (fields.size < 4) return null
        val weight10 = fields[0].toIntOrNull() ?: return null
        // 量程：HX711 满量程按 ±5kg 算也只有 ±50000，超了就是坏帧
        if (weight10 < -50_000 || weight10 > 50_000) return null
        val body = fields[1].toIntOrNull() ?: return null
        // 量程：12 位 ADC
        if (body < 0 || body > 4095) return null
        val stable = when (fields[2]) {
            "1", "true", "YES", "yes" -> true
            else -> false
        }
        val mark = fields[3].toIntOrNull() ?: return null
        // 量程：mark 是"按键次数"，一天按几百次就很夸张了；10 万当作硬上限
        if (mark < 0 || mark > MAX_MARK) return null
        val battery = fields.getOrNull(4)?.toIntOrNull() ?: -1
        return Status(weight10, body, stable, mark, battery)
    }

    /** `mark` 的硬上限（超过就认为这一帧是坏的）。 */
    const val MAX_MARK = 100_000
}
