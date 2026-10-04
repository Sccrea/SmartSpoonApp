package com.equimeal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙协议的**离线**自检（`gradlew :app:testDebugUnitTest`，不需要真机也不需要模拟器）。
 *
 * 为什么这几条测试值得写：
 * - 协议解析是"App 与固件之间唯一的接口"，它错了就是**静默出错**（读数不动、
 *   口数不涨、电量乱跳），而且现场排查成本很高（要拿真机、要连电脑看串口）；
 * - 另一半原因是 BLE 的 **半包/粘包**：`SpoonLink` 收到的一包不保证是一行。
 *   这件事在真机上偶发、在测试里可以 100% 复现（[FrameReaderTest] 第 2 条就是干这个的）。
 *
 * 固件那一侧的对应实现是 `C:\Users\Administrator\Desktop\t8\t8.ino` 的
 * `sendBLEState()` / `sendBLEMark()`；这里用的样例字符串就是从它那里抄下来的格式。
 */
class SpoonProtocolTest {

    /* ------------------------------------------------------------ 状态帧 */

    @Test
    fun `解析固件实际发出来的状态帧`() {
        // t8.ino: bleuart.print("S,"); weight10; body; stable?1:0; markCount; battery
        val event = SpoonProtocol.parseLine("S,123,2048,1,7,96")
        assertNotNull(event)
        assertEquals('S', event!!.kind)
        assertEquals(listOf("123", "2048", "1", "7", "96"), event.fields)

        val status = SpoonProtocol.parseStatus(event.fields)
        assertNotNull(status)
        assertEquals(123, status!!.weight10)
        assertEquals(12.3, status.weightGrams, 1e-9)
        assertEquals(2048, status.body)
        assertTrue(status.stable)
        assertEquals(7, status.mark)
        assertEquals(96, status.battery)
    }

    @Test
    fun `老固件没有电量的 5 字段版本也要能解析`() {
        val status = SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,-320,1500,0,3")!!.fields)
        assertNotNull(status)
        assertEquals(-32.0, status!!.weightGrams, 1e-9)   // 没去皮时读数可以是负的
        assertEquals(-1, status.battery)                   // -1 = 固件没报电量
    }

    @Test
    fun `字段坏了宁可丢这一帧也不要造出假读数`() {
        assertNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,abc,2048,1,7")!!.fields))
        assertNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,123,2048,1")!!.fields))
        assertNull(SpoonProtocol.parseStatus(emptyList()))
    }

    /**
     * 这条测试来自真机现场：手机上出现过 `mark` 解析成 **5100 / 9100** 的帧，
     * 那个垃圾值进了"口数去重基准"之后，所有正常 mark（7、8、9…）全被判"已处理过"，
     * 表现就是**按 SW3 永远不记口**。所以量程检查是必需的。
     */
    @Test
    fun `超量程的字段必须整帧丢掉`() {
        // mark 超上限（现场见过的 5100 虽然没超上限，但 9100 这类粘连值会越来越大，
        // 而且真正的坏帧里 weight/body 也经常一起离谱）
        assertNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,1255,970,1,999999")!!.fields))
        assertNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,1255,970,1,$100001")!!.fields))
        assertNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,1255,970,1,-5")!!.fields))
        // body 超出 12 位 ADC
        assertNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,1255,99999,1,7")!!.fields))
        // weight 超出 ±5kg 的合理量程
        assertNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,999999,970,1,7")!!.fields))
        // 正常帧仍然照样能过
        assertNotNull(SpoonProtocol.parseStatus(SpoonProtocol.parseLine("S,1255,970,1,7,96")!!.fields))
    }

    @Test
    fun `两帧粘成一行时只取前面的完整帧并保证 mark 不越界`() {
        // 现场出现过的形态：`S,1255,970,1,7,5100,9100`（两个数字粘在一起）
        val line = "S,1255,970,1,7,5100,9100"
        val event = SpoonProtocol.parseLine(line)
        assertNotNull(event)
        val status = SpoonProtocol.parseStatus(event!!.fields)
        // 粘帧本身仍有 4 个合法字段，所以这条会解析成功 —— 关键是 mark 取到的是 7（不是 5100）
        assertNotNull(status)
        assertEquals(7, status!!.mark)
    }

    @Test
    fun `固件启动时打到 Notify 上的日志要被忽略而不是报错`() {
        // Serial 日志不可能跑到 Notify 上，但协议对"看不懂的行"必须是宽容的：
        // 现场排障时经常混着人看的文本，一条不认识的行不该影响数据流
        assertNull(SpoonProtocol.parseLine("IMU OK"))
        assertNull(SpoonProtocol.parseLine("Weight: 12.3 g | Body: 2048"))
        assertNull(SpoonProtocol.parseLine(""))
        assertNull(SpoonProtocol.parseLine("   "))
    }

    @Test
    fun `事件帧与应答帧`() {
        val bite = SpoonProtocol.parseLine("B,8,127")!!
        assertEquals('B', bite.kind)
        assertEquals("8", bite.fields[0])

        val ver = SpoonProtocol.parseLine("A,VER,1.1")!!
        assertEquals('A', ver.kind)
        assertEquals(listOf("VER", "1.1"), ver.fields)

        val error = SpoonProtocol.parseLine("E,CMD,unknown command")!!
        assertEquals('E', error.kind)
        assertEquals("unknown command", error.fields[1])

        // 行尾的 \r（串口助手手敲命令时会带）要被吃掉
        assertEquals(listOf("ZERO", "1"), SpoonProtocol.parseLine("A,ZERO,1\r")!!.fields)
    }

    /* -------------------------------------------------------- 命令（下行） */

    @Test
    fun `下行命令的格式与固件对齐`() {
        assertEquals("ZERO", SpoonProtocol.CMD_ZERO)
        assertEquals("PING", SpoonProtocol.CMD_PING)
        assertEquals("VER?", SpoonProtocol.CMD_VER)
        assertEquals("MODE 2", SpoonProtocol.cmdMode(2))
        // 固件 handleCommand() 里比的就是这两个词（大写化之后）
        assertEquals("TARE", SpoonProtocol.CMD_TARE)
    }
}

/**
 * 「一包不等于一行」这个 BLE 的经典坑，在这里被完整复现。
 *
 * [SpoonLink] 与 [SpoonSelfTest] 共用同一个 [FrameReader]，所以这组测试保护的是**两处**：
 * 真机的 Notify 路径，以及自测路径。
 */
class FrameReaderTest {

    @Test
    fun `一包含多帧要全部切出来`() {
        val reader = FrameReader()
        val lines = reader.feed("S,100,2000,1,1,96\nS,200,2000,1,1,96\nS,300,2000,0,1,95\n")
        assertEquals(3, lines.size)
        assertEquals("S,100,2000,1,1,96", lines[0])
        assertEquals("S,300,2000,0,1,95", lines[2])
    }

    @Test
    fun `半包要攒到下一包再切 - 而且不能丢帧也不能多帧`() {
        val reader = FrameReader()
        // 蓝牙按 MTU 切包，一帧正好被切在中间
        assertEquals(emptyList<String>(), reader.feed("S,12"))
        assertEquals(emptyList<String>(), reader.feed("3,2048,1,"))
        assertEquals(listOf("S,123,2048,1,7,96"), reader.feed("7,96\n"))
        // 之后来的完整帧照常
        assertEquals(listOf("S,50,2000,0,8,96"), reader.feed("S,50,2000,0,8,96\n"))
    }

    @Test
    fun `一个字节一个字节地喂也要能切出帧`() {
        val reader = FrameReader()
        val frame = "S,42,2048,1,9,88\n"
        val out = mutableListOf<String>()
        for (c in frame) out.addAll(reader.feed(c.toString()))
        assertEquals(listOf("S,42,2048,1,9,88"), out)
    }

    @Test
    fun `没有换行的垃圾数据不会把缓冲撑爆`() {
        // 上限设得很小，专门逼出"裁剪"那条路径（默认 4096 在真机上要跑很久才到）
        val reader = FrameReader(maxBuffer = 256)
        val field = FrameReader::class.java.getDeclaredField("buffer").apply { isAccessible = true }

        // 对端一直不发换行，喂 20 KB 垃圾
        repeat(2000) {
            reader.feed("junkjunkjunkjunk")
            assertTrue(
                "缓冲必须被限制在 maxBuffer 附近，不能无限增长",
                (field.get(reader) as StringBuilder).length <= 256,
            )
        }
        // 裁剪之后，后面来的正常帧仍然要能被完整地切出来。
        // 注意断言的是 "以这个帧**结尾**"：裁剪只保证缓冲有上限，不承诺把残留的垃圾字符
        // 也从这一行里去掉（那是解析器的责任，见 parseLine 的说明）。
        val lines = reader.feed("S,1,2,1,3,90\n")
        assertEquals(1, lines.size)
        assertTrue("帧本身必须完整", lines[0].endsWith("S,1,2,1,3,90"))
        assertEquals(90, SpoonProtocol.parseStatus(SpoonProtocol.parseLine(lines[0])!!.fields)!!.battery)
    }

    @Test
    fun `clear 之后不会把上一台勺子的半个帧拼到下一台上`() {
        val reader = FrameReader()
        reader.feed("S,999,20")
        reader.clear()
        // 新设备从完整帧开始
        assertEquals(listOf("S,10,2000,1,1,100"), reader.feed("S,10,2000,1,1,100\n"))
    }

    @Test
    fun `换了一台设备时残留的垃圾前缀不该毒掉后面的好帧`() {
        // 真机上可能出现：上一次连接留下的垃圾/半个帧没清干净（`clear()` 与 Notify 抢时序），
        // 于是垃圾粘在一个**完整的好帧**前面。整行判废的话，后面那个好帧就一起丢了 ——
        // 现场表现就是"数据流突然断了"。所以解析器要能从垃圾里把帧捞出来。
        val reader = FrameReader()
        // 第一段：垃圾 + 半个旧帧（还没有换行，什么都切不出来）
        assertEquals(emptyList<String>(), reader.feed("junk 77,20"))
        // 第二段："junk 77,20" 的末尾不是 `S,` 也不是逗号，所以整个缓冲区里
        // **只有**新来的这半行带合法帧头 —— 与真机上残留的场景一致
        val lines = reader.feed("S,77,2000,1,4,95\n")
        assertEquals(1, lines.size)
        assertTrue("整行确实粘着垃圾", lines[0].startsWith("junk 77"))

        // 解析器从 `S,` 处把帧捞出来（见 SpoonProtocol.parseLine 的说明）
        val event = SpoonProtocol.parseLine(lines[0])
        assertNotNull(event)
        assertEquals('S', event!!.kind)
        val status = SpoonProtocol.parseStatus(event.fields)
        assertNotNull(status)
        assertEquals(77, status!!.weight10)
        assertEquals(95, status.battery)
    }
}
