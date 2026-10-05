package com.equimeal.gramo

/**
 * 把「手机收到的字节流」切成一行一行的协议帧。
 *
 * ## 为什么需要它（而不是收到就 `String(...).split("\n")`）
 *
 * BLE 的 Notify **不保证"一包 = 一行"**：
 * - 固件 `bleuart.println("S,123,2000,1,7,96")` 是 1 次写入，但蓝牙栈会按 MTU 切分，
 *   一帧可能分成两次 Notify 到达（半包）；
 * - 反过来，手机把 CCCD 打开的一瞬间，缓冲里可能已经攒了三四帧，一次 Notify 全送来（粘包）。
 *
 * 所以必须像串口那样攒着——**只有见到 `\n` 才算一行**，剩下的留在缓冲里等下一包。
 *
 * 半包与粘包这两条路径由**单元测试**直接喂字节来覆盖
 * （见 `app/src/test/java/com/smartspoon/l2/SpoonProtocolTest.kt`：把一帧拆成两半、
 * 或把三帧拼成一包喂进来）。原先还有一个 App 内的"模拟勺子"自测开关做同一件事，
 * 那个开关已经删掉了（勺子数据只来自真机），但这条覆盖并没有丢：它本来就更适合待在测试里。
 *
 * 注意：**不是线程安全**的 —— 只在主线程用它。
 */
class FrameReader(
    /** 缓冲上限：对端一直不发换行时的保护（正常帧只有几十字节）。 */
    private val maxBuffer: Int = 4096,
) {
    private val buffer = StringBuilder()

    /**
     * 喂入一段数据（可能包含半行、整行、多行）。
     *
     * @return 切出来的完整行（不含换行符，已 trim）；没有完整行时返回空列表。
     */
    fun feed(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)

        val lines = mutableListOf<String>()
        var index = buffer.indexOf("\n")
        while (index >= 0) {
            val line = buffer.substring(0, index).trim()
            buffer.delete(0, index + 1)
            if (line.isNotEmpty()) lines.add(line)
            index = buffer.indexOf("\n")
        }

        /*
         * 缓冲上限的保护放在**切完之后**，而且以"最后一个换行"为界往后留。
         *
         * 顺序与取舍都是有理由的：
         * - 先切再裁：如果先裁剪再切行，一旦缓冲超限，**同一包里的完整帧**会被左移成
         *   一个"前面粘了垃圾"的行，然后被 [SpoonProtocol.parseLine] 拒掉 ——
         *   表现就是"偶尔丢一帧"，只在异常对端下复现，极难排查。
         * - 裁的时候保留**最新**的一段（而不是最老的一段）：真的出现"对端不发换行"时，
         *   新数据比旧数据有用，保留最新段能让解析尽快恢复正常。
         */
        if (buffer.length > maxBuffer) {
            val lastBreak = buffer.lastIndexOf("\n")
            if (lastBreak >= 0) {
                buffer.delete(0, lastBreak + 1)
            } else {
                // 一个换行都没有：整段都是半行，留下最新的 1/4
                buffer.delete(0, buffer.length - maxBuffer / 4)
            }
        }
        return lines
    }

    /** 丢弃半行残留（断开连接时调用，免得把上一台的半个帧拼到下一台上）。 */
    fun clear() {
        buffer.setLength(0)
    }
}
