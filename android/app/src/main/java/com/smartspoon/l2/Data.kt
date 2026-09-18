package com.smartspoon.l2

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/* ------------------------------------------------------------------ 模型 */

data class Food(
    val id: String,
    val name: String,
    val category: String,
    val density: Double,
    val times: Int,
    val img: String,
)

data class MenuDef(val id: String, val name: String, val foods: List<String>)
data class FolderDef(val id: String, val name: String, val foods: List<String>)

data class MealRecord(
    val no: Int,
    val menu: String,
    val foods: List<String>,
    val bites: Int,
    /** 基准单位 g（库里的列本来就是 REAL，之前读成 Int 会把小数抹掉）。 */
    val weight: Double,
    /** 基准单位 kJ（同上）。 */
    val energy: Double,
    val start: String,
    val end: String,
    val id: Long = 0,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
)

/**
 * 一台智味勺。
 *
 * 不是 data class：`name`/`battery`/`saved` 会在原地被改（服务器轮询刷新电量、
 * 用户点「保存 / 取消保存」），所以它们必须是 Compose 可观察属性，界面才会跟着变。
 * 构造参数名字保持不变，原来的具名/位置调用都不用改。
 */
class Device(
    val id: String,
    name: String,
    battery: Int,
    saved: Boolean,
    val nearby: Boolean,
    val picker: Boolean,
) {
    var name by mutableStateOf(name)
    var battery by mutableStateOf(battery)
    var saved by mutableStateOf(saved)
}

data class User(val name: String)

data class ChartPoint(val x: String, val y: Int)
data class ChartData(
    val xLabel: String,
    val yLabel: String,
    val yTicks: List<Int>,
    val xTicks: List<String>,
    val points: List<ChartPoint>,
)

/**
 * 用餐结果页可修正的数据。
 *
 * 这里存的是**数值 + 基准单位**（分钟 / 口 / g / kJ），不是拼好的字符串 ——
 * 显示时由 [Units] 按「设置 → 杂项」里的单位现算。以前存的是 `"359 g"` 这种成品字符串，
 * 于是改了重量单位，结果页上那几行还是老单位。
 */
class MealResult(
    savedNo: Int = 4,
    minutes: Int = 49,
    bites: Int = 25,
    weightGrams: Double = 359.0,
    energyKj: Double = 1653.0,
    avgWeightGrams: Double = 14.4,
    avgEnergyKj: Double = 66.1,
) {
    var savedNo by mutableStateOf(savedNo)
    var minutes by mutableStateOf(minutes)
    var bites by mutableStateOf(bites)
    var weightGrams by mutableStateOf(weightGrams)
    var energyKj by mutableStateOf(energyKj)
    var avgWeightGrams by mutableStateOf(avgWeightGrams)
    var avgEnergyKj by mutableStateOf(avgEnergyKj)
}

data class Bootstrap(
    val foods: MutableList<Food>,
    val menus: MutableList<MenuDef>,
    val categories: List<String>,
    val folders: List<FolderDef>,
    val favoriteTimes: Map<String, Long>,
    val records: List<MealRecord>,
    val stats: List<Pair<String, String>>,
    val chart: ChartData,
    val devices: MutableList<Device>,
    val user: User,
    val preselect: List<String>,
    val result: MealResult,
    val dishesUpdated: String,
)

/* --------------------------------------------------------------- JSON 解析 */

private fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

/**
 * 取字符串里的第一个数字。
 *
 * 服务器（老的 View 版后端）把结果字段发成 `"359 g"` / `"1653 kJ"` / `"49 分钟"` 这种
 * 已经拼好的文本，而本应用内部只存基准单位的数值，所以这里把数字抠出来。
 */
private fun firstNumber(text: String, fallback: Double): Double =
    Regex("-?[0-9]+(?:\\.[0-9]+)?").find(text)?.value?.toDoubleOrNull() ?: fallback

/** 把老后端格式化过的中文时间文本尽量还原成时间戳；认不出来返回 0（界面显示「—」）。 */
private fun parseLegacyTime(text: String): Long = Units.parseLegacyDateTime(text)

fun parseFood(o: JSONObject) = Food(
    id = o.str("id"),
    name = o.str("name"),
    category = o.str("category", "其他"),
    density = o.optDouble("density", 0.0),
    times = o.optInt("times", 0),
    img = o.str("img", "congee.svg"),
)

private fun stringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.optString(it) }
}

fun parseBootstrap(o: JSONObject): Bootstrap {
    val foods = mutableListOf<Food>()
    o.optJSONArray("foods")?.let { array ->
        for (i in 0 until array.length()) foods.add(parseFood(array.getJSONObject(i)))
    }
    val menus = mutableListOf<MenuDef>()
    o.optJSONArray("menus")?.let { array ->
        for (i in 0 until array.length()) {
            val m = array.getJSONObject(i)
            menus.add(MenuDef(m.str("id"), m.str("name"), stringList(m.optJSONArray("foods"))))
        }
    }
    val folders = mutableListOf<FolderDef>()
    o.optJSONArray("folders")?.let { array ->
        for (i in 0 until array.length()) {
            val f = array.getJSONObject(i)
            folders.add(FolderDef(f.str("id"), f.str("name"), stringList(f.optJSONArray("foods"))))
        }
    }
    val records = mutableListOf<MealRecord>()
    o.optJSONArray("records")?.let { array ->
        for (i in 0 until array.length()) {
            val r = array.getJSONObject(i)
            records.add(
                MealRecord(
                    no = r.optInt("no", i + 1),
                    menu = r.str("menu"),
                    foods = stringList(r.optJSONArray("foods")),
                    bites = r.optInt("bites"),
                    weight = r.optDouble("weight", 0.0),
                    energy = r.optDouble("energy", 0.0),
                    start = r.str("start"),
                    end = r.str("end"),
                )
            )
        }
    }
    val stats = mutableListOf<Pair<String, String>>()
    o.optJSONObject("stats")?.let { s ->
        for (key in s.keys()) stats.add(key to s.str(key))
    }
    val devices = mutableListOf<Device>()
    o.optJSONArray("devices")?.let { array ->
        for (i in 0 until array.length()) {
            val d = array.getJSONObject(i)
            devices.add(
                Device(
                    id = d.str("id"),
                    name = d.str("name"),
                    battery = d.optInt("battery", 60),
                    saved = d.optBoolean("saved", false),
                    nearby = d.optBoolean("nearby", true),
                    picker = d.optBoolean("picker", true),
                )
            )
        }
    }
    val favoriteTimes = mutableMapOf<String, Long>()
    o.optJSONObject("favoriteTimes")?.let { t ->
        // 服务器发来的是**格式化好的文本**（老后端的字段），这里尽量还原成时间戳；
        // 认不出来就记 0，界面会显示成「—」。本地库那条路径不受影响。
        for (key in t.keys()) favoriteTimes[key] = parseLegacyTime(t.str(key))
    }
    val chartJson = o.optJSONObject("chart") ?: JSONObject()
    val yTicks = mutableListOf<Int>()
    chartJson.optJSONArray("y_ticks")?.let { for (i in 0 until it.length()) yTicks.add(it.optInt(i)) }
    val points = mutableListOf<ChartPoint>()
    chartJson.optJSONArray("points")?.let { array ->
        for (i in 0 until array.length()) {
            val p = array.getJSONObject(i)
            points.add(ChartPoint(p.str("x"), p.optInt("y")))
        }
    }
    val resultJson = o.optJSONObject("result") ?: JSONObject()
    return Bootstrap(
        foods = foods,
        menus = menus,
        categories = stringList(o.optJSONArray("categories")).ifEmpty { listOf("全部", "肉类", "蔬菜", "水果") },
        folders = folders,
        favoriteTimes = favoriteTimes,
        records = records,
        stats = stats,
        chart = ChartData(
            xLabel = chartJson.str("x_label", "用餐完成时间"),
            yLabel = chartJson.str("y_label", "摄入能量/kJ"),
            yTicks = yTicks.ifEmpty { listOf(0, 200, 400, 600, 800, 1000, 1200) },
            xTicks = stringList(chartJson.optJSONArray("x_ticks")),
            points = points,
        ),
        devices = devices,
        user = User(o.optJSONObject("user")?.str("name", "张三") ?: "张三"),
        preselect = stringList(o.optJSONArray("preselect")),
        result = MealResult(
            savedNo = resultJson.optInt("savedNo", 4),
            minutes = firstNumber(resultJson.str("duration", "49"), 49.0).toInt(),
            bites = firstNumber(resultJson.str("bites", "25"), 25.0).toInt(),
            weightGrams = firstNumber(resultJson.str("weight", "359"), 359.0),
            energyKj = firstNumber(resultJson.str("energy", "1653"), 1653.0),
            avgWeightGrams = firstNumber(resultJson.str("avgWeight", "14.4"), 14.4),
            avgEnergyKj = firstNumber(resultJson.str("avgEnergy", "66.1"), 66.1),
        ),
        dishesUpdated = o.str("dishesUpdated"),
    )
}

/* ------------------------------------------------------------------ 网络 */

object Api {
    private fun open(url: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }

    private fun read(connection: HttpURLConnection): String? = try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use(BufferedReader::readText)
        if (code in 200..299) body else null
    } catch (e: Exception) {
        null
    } finally {
        connection.disconnect()
    }

    fun getJson(base: String, path: String, timeoutMs: Int = 4000): JSONObject? {
        return try {
            val connection = open(base + path, timeoutMs)
            connection.requestMethod = "GET"
            read(connection)?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun postJson(base: String, path: String, body: JSONObject): JSONObject? {
        return try {
            val connection = open(base + path, 8000)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            read(connection)?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(base: String, path: String): JSONObject? {
        return try {
            val connection = open(base + path, 8000)
            connection.requestMethod = "DELETE"
            read(connection)?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** 上传图片识别，返回 /api/recognize 的 JSON。 */
    fun recognize(base: String, bytes: ByteArray, fileName: String): JSONObject? {
        val boundary = "----smartspoon" + System.currentTimeMillis()
        return try {
            val connection = open(base + "/api/recognize", 20000)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { out ->
                out.write(("--$boundary\r\n").toByteArray())
                out.write(
                    ("Content-Disposition: form-data; name=\"image\"; filename=\"$fileName\"\r\n")
                        .toByteArray()
                )
                out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                out.write(bytes)
                out.write("\r\n".toByteArray())
                out.write(("--$boundary--\r\n").toByteArray())
            }
            read(connection)?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** 读取服务器模拟的勺子状态（since 之后的每一口会放在 newBites 里）。 */
    fun deviceState(base: String, since: Int): JSONObject? =
        getJson(base, "/api/device/state?since=$since", 2000)

    fun postDevice(base: String, path: String, body: JSONObject): JSONObject? =
        postJson(base, path, body)

    fun probe(base: String, timeoutMs: Int = 1500): Boolean {
        val json = getJson(base, "/api/health", timeoutMs) ?: return false
        return json.optString("status") == "ok"
    }

    fun fetchBootstrap(base: String): Bootstrap? {
        val json = getJson(base, "/api/bootstrap", 6000) ?: return null
        val data = json.optJSONObject("data") ?: return null
        return parseBootstrap(data)
    }

    /** 把菜品保存到服务器（应用里「加入菜单」时调用）。 */
    fun saveDish(base: String, name: String, category: String, density: Double, times: Int): Food? {
        val body = JSONObject()
            .put("name", name)
            .put("category", category)
            .put("density", density)
            .put("times", times)
        val json = postJson(base, "/api/dishes", body) ?: return null
        val food = json.optJSONObject("food") ?: return null
        return parseFood(food)
    }
}

/* ------------------------------------------------------------------ 状态 */

/**
 * 主界面（`MainActivity`）还剩哪几个页面。
 *
 * 阶段 3 之后「选择本餐菜单 / 自定义本餐菜单 / 用餐中 / 用餐结果」以及设置子页、统计数据
 * 都是**独立 Activity**，不再由这个密封类表达——它们的页面身份就是 Activity 自己。
 * 剩下这两个：应用刚启动时的快速开始首页，以及底部标签页（具体是哪一页由 [State.tab]
 * 与 [State.settingsView] 决定）。
 */
sealed class Screen {
    object QuickStart : Screen()

    /** 收藏食物 / 用餐记录 / 设置：由 State.tab 与 State.settingsView 决定具体页面。 */
    object Tab : Screen()
}

/**
 * 当前的弹窗（旧版那一圈 `android.app.Dialog`）。
 *
 * 弹窗不再由 MainActivity 直接拼 View，而是把「要显示哪一个」写进 [State.dialog]，
 * 由 Compose 的 `AppDialogs` 渲染。这里存的是**标识**（菜品 id、字段名…）而不是界面对象：
 * 需要展示的数据一律在渲染时从 [State] 现读，所以弹窗开着的时候也能跟着数据变化刷新。
 */
sealed class AppDialog {

    /** p.04 / p.05 连接智味勺：picking = 显示附近的勺子列表（重新选择），否则显示当前连接。 */
    data class Connect(val picking: Boolean) : AppDialog()

    /** p.06 用餐提醒。 */
    object Remind : AppDialog()

    /** 设置 → 杂项：服务器地址。 */
    object Server : AppDialog()

    /** 用餐结果 → 修正数据：field = duration / bites / weight / energy。 */
    data class EditResult(val field: String) : AppDialog()

    /** 用餐结果 → 每口详细数据（本次用餐）。 */
    object BiteDetail : AppDialog()

    /** 用餐记录 → 某条记录的明细。 */
    data class MealDetail(val record: MealRecord) : AppDialog()

    /** 新增（foodId == null）/ 编辑菜品。 */
    data class DishEditor(val foodId: String?) : AppDialog()

    /** 删除菜品确认。 */
    data class DeleteDish(val foodId: String) : AppDialog()

    /** 拍照识别：选「拍照」还是「选择图片」。 */
    object Recognize : AppDialog()

    /** 正在识别… */
    object Recognizing : AppDialog()

    /** 识别结果：message == null 表示成功（items 是 Top count 的结果）。 */
    data class RecognizeResult(
        val message: String?,
        val count: Int,
        val items: List<RecognizeItem>,
    ) : AppDialog()
}

/** 识别出来的一道菜（旧版直接读 JSON，这里先解析成纯数据再交给界面）。 */
data class RecognizeItem(val name: String, val percent: String, val calorie: Double)

/**
 * 用餐中的实时数值。
 *
 * 字段用 Compose 的 `mutableStateOf` 承载：写入方（服务器轮询线程、计时器）完全不用改，
 * 读取方在 Compose 里会自动重组。
 */
class MealState {
    var minutes by mutableStateOf(0)
    var food by mutableStateOf("小米粥")
    var spoonWeight by mutableStateOf(0)
    var spoonEnergy by mutableStateOf(0.0)
    var bites by mutableStateOf(0)
    var totalWeight by mutableStateOf(0)
    var totalEnergy by mutableStateOf(0.0)
    var paused by mutableStateOf(false)
    var startedAt by mutableStateOf(0L)
}

object State {
    private const val PREFS = "smartspoon"
    private const val KEY_SERVER = "server_url"
    const val DEFAULT_SERVER = "https://sccrea64.cc.cd:16384"
    val FALLBACK_SERVERS = listOf("https://sccrea64.cc.cd:16384")

    /*
     * 下面所有可变字段都用 Compose 的 `mutableStateOf` 承载。
     *
     * 这是整个 Compose 迁移的桥：State 仍然是唯一的真相来源，Store / 网络 / 相机 / 计时
     * 那些逻辑的读写写法一个字都不用改，但 Compose 界面会自动跟着重组——
     * 原来那套 `render()` / `renderContentOnly()` 的整页重建机制因此彻底不需要了。
     */
    var server by mutableStateOf(DEFAULT_SERVER)
    var online by mutableStateOf(false)
    /** 勺子数据是否在线（用餐中由服务器模拟推送）。 */
    var deviceOnline by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    var data by mutableStateOf<Bootstrap?>(null)

    var tab by mutableStateOf(0)      // 0 快速开始 / 1 收藏食物 / 2 用餐记录 / 3 设置
    var screen by mutableStateOf<Screen>(Screen.QuickStart)

    var settingsView by mutableStateOf("")
    var deviceScope by mutableStateOf("saved")
    var statsPage by mutableStateOf(1)

    var category by mutableStateOf("全部")
    var query by mutableStateOf("")
    var sortDesc by mutableStateOf(true)
    var folderId by mutableStateOf<String?>(null)
    val selected = mutableStateListOf<String>()

    // 统计数据页的下拉选择
    var axisX by mutableStateOf("用餐完成时间")
    var axisY by mutableStateOf("摄入能量")
    var chartStyle by mutableStateOf("折线统计图")
    var chartRange by mutableStateOf("全部")
    var axisScale by mutableStateOf("线性")

    var connectedId by mutableStateOf<String?>("s1")
    var autoConnect by mutableStateOf(true)
    var showYear by mutableStateOf(true)
    var showSecond by mutableStateOf(false)
    var energyUnit by mutableStateOf("kJ")
    var weightUnit by mutableStateOf("g")

    val meal = MealState()
    var result by mutableStateOf(MealResult())

    /** 当前弹窗：null = 没有弹窗（旧版就是「没有 overlay」）。 */
    var dialog by mutableStateOf<AppDialog?>(null)

    /**
     * 用餐中「＋ 添加食物」的临时模式：为 true 时正停在选菜页加菜。
     *
     * 这段时间记录是暂停的（[MealState.paused] 为 true 且已停掉服务器轮询），
     * 加完返回（「完成添加」或系统返回）会恢复记录并回到「用餐中」。
     */
    var addingFood by mutableStateOf(false)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        server = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        // 用户的设置选择（单位、时间显示、自动连接）
        energyUnit = prefs.getString("energy_unit", "kJ") ?: "kJ"
        weightUnit = prefs.getString("weight_unit", "g") ?: "g"
        showYear = prefs.getBoolean("show_year", true)
        showSecond = prefs.getBoolean("show_second", false)
        autoConnect = prefs.getBoolean("auto_connect", true)
    }

    fun saveServer(context: Context, value: String) {
        server = normalize(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER, server).apply()
    }

    fun normalize(value: String): String {
        var v = value.trim()
        if (v.isEmpty()) v = DEFAULT_SERVER
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        while (v.endsWith("/")) v = v.dropLast(1)
        return v
    }

    fun food(id: String?): Food? = data?.foods?.firstOrNull { it.id == id }

    fun connectedDevice(): Device? = data?.devices?.firstOrNull { it.id == connectedId }

    fun sortedFoods(): List<Food> {
        val list = (data?.foods ?: emptyList()).toMutableList()
        list.sortBy { it.times }
        if (sortDesc) list.reverse()
        return list
    }

    fun filteredFoods(): List<Food> {
        var list = sortedFoods()
        if (category != "全部") list = list.filter { it.category == category }
        if (query.isNotEmpty()) list = list.filter { it.name.contains(query, ignoreCase = true) }
        return list
    }

    /**
     * 缩略图用的 emoji。刻意只挑 Unicode 6.0 以内的字符：
     * 模拟器自带的 emoji 字体较旧，🥤🥗🥬🥣 这类新码位会显示成方框。
     */
    val emoji: Map<String, String> = mapOf(
        "pizza.svg" to "🍕", "cake.svg" to "🍰", "burger.svg" to "🍔", "lamb.svg" to "🍖",
        "soda.svg" to "🍹", "salad.svg" to "🍃", "melon.svg" to "🍈", "congee.svg" to "🍚",
        "greens.svg" to "🌿", "pork.svg" to "🍗", "noodle.svg" to "🍜", "apple.svg" to "🍎",
    )

    /** 缩略图字符：数据库里直接存 emoji；兼容旧的 .svg 资源名。 */
    fun emojiFor(food: Food?): String {
        val img = food?.img ?: return "🍽"
        return if (img.endsWith(".svg")) emoji[img] ?: "🍽" else img
    }
}
