package com.equimeal

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
    /**
     * 收藏时间（毫秒）；0 = 没收藏，或这行是 v1 升级上来的、老文本没解析出时间。
     *
     * 由 [Store.dishes] 一并读出来，让"按收藏时间排序"和"显示收藏时间"都不必再回头查表。
     */
    val favoriteAtMs: Long = 0L,
    /** 是否已收藏。与 `favoriteAtMs > 0` 不完全等价：老行可能收藏了但没有时间。 */
    val favorite: Boolean = favoriteAtMs > 0L,
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
 * 一顿饭的「云端形态」。
 *
 * 与 [MealRecord] 的区别：那个是**给界面看的**（序号、格式化好的时间、菜品名列表），
 * 这个是**给服务器存的**（纯数据，口味上跟数据库列一一对应）。两件事混在一个类里会出现
 * "为了上传得先把界面字段凑齐"这种别扭事。
 *
 * [key] 是幂等键：用**结束时刻**当标识 —— 同一顿饭在两台设备上分别上报时，
 * 结束时刻一致就能被服务器认成同一条（见 `AccountStore.merge_meal_records`）。
 */
data class CloudRecord(
    val key: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val bites: List<Bite>,
)

/**
 * 一台智味勺。
 *
 * 不是 data class：`name`/`battery`/`saved` 会在原地被改（服务器轮询刷新电量、
 * 用户点「保存 / 取消保存」），所以它们必须是 Compose 可观察属性，界面才会跟着变。
 * 构造参数名字保持不变，原来的具名/位置调用都不用改。
 *
 * [protocol] 说明这台设备"数据从哪来"，从 [BleSpoon.attach] 接上真机之后这一栏是必要的：
 * 服务器那份示例设备（`s1`）是模拟读数，真勺子（MAC 地址作 id）走 BLE。
 * 界面据此显示不同的电量/状态文案，「断开连接」也要分别处理。
 */
class Device(
    val id: String,
    name: String,
    battery: Int,
    saved: Boolean,
    val nearby: Boolean,
    val picker: Boolean,
    val protocol: String = PROTOCOL_SERVER,
) {
    var name by mutableStateOf(name)
    var battery by mutableStateOf(battery)
    var saved by mutableStateOf(saved)

    /** 数据来源：[PROTOCOL_BLE] = 真机蓝牙，[PROTOCOL_SERVER] = 服务器模拟。 */
    val isBle: Boolean get() = protocol == PROTOCOL_BLE

    companion object {
        const val PROTOCOL_SERVER = "server"
        const val PROTOCOL_BLE = "ble"
    }
}

data class User(val name: String)

/**
 * 当前登录的账号。
 *
 * 刻意**不存密码**：登录成功后服务器发一个令牌，之后全部请求带令牌就行
 * （见 `Api.login` / `Api.register`）。这样即使手机被人拿到，也看不到密码。
 *
 * 字段都是 Compose 可观察的：界面直接读，登录/退出后会自动重组。
 */
class Account(
    username: String,
    name: String,
    avatar: String,
    token: String,
) {
    /** 登录用户名（同时决定用哪个本地库文件，见 [Store.forAccount]）。 */
    val username: String = username

    var name by mutableStateOf(name)
    var avatar by mutableStateOf(avatar)

    /** 登录令牌（`Authorization: Bearer <token>`）。 */
    val token: String = token

    /** 界面显示用的名字：昵称优先，没有就用用户名。 */
    val displayName: String get() = name.ifBlank { username }
}

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
    /**
     * 这一餐叫什么。
     *
     * 由「使用现有菜单快速开始」带过来的是**那份菜单的名字**，自定义菜单带过来的是
     * 「自定义菜单」。结果页把它显示出来，点一下可以改（改名同时写回用餐记录）。
     */
    mealName: String = "自定义菜单",
) {
    var savedNo by mutableStateOf(savedNo)
    var minutes by mutableStateOf(minutes)
    var bites by mutableStateOf(bites)
    var weightGrams by mutableStateOf(weightGrams)
    var energyKj by mutableStateOf(energyKj)
    var avgWeightGrams by mutableStateOf(avgWeightGrams)
    var avgEnergyKj by mutableStateOf(avgEnergyKj)
    var mealName by mutableStateOf(mealName)
}

/**
 * 「用餐记录详情页」正在编辑的那一条记录。
 *
 * 为什么单独做一个可变状态类，而不是复用 [MealResult]：
 * [MealResult] 描述的是"刚刚吃完的这一餐"（它是 `MealSession.finishMeal` 的产物，
 * 保存后就走 [MealSession.doneResult] 那条路）。而这里是**翻历史记录去改**，
 * 两者生命周期完全不同，混用会出现"改了一条历史记录，结果页也跟着变了"这种串台。
 *
 * 字段与结果页一一对应：用户看到的就是结果页那一套（时长/口数/重量/热量 + 平均两项），
 * 所以两个页面能共用同一批行组件 [ui.MealDataRows]。
 */
class MealEditor(
    val id: Long,
    name: String,
    minutes: Int,
    bites: Int,
    weightGrams: Double,
    energyKj: Double,
    val startedAt: Long,
    val endedAt: Long,
    val biteList: List<Bite>,
) {
    var name by mutableStateOf(name)
    var minutes by mutableStateOf(minutes)
    var bites by mutableStateOf(bites)
    var weightGrams by mutableStateOf(weightGrams)
    var energyKj by mutableStateOf(energyKj)

    val avgWeightGrams: Double get() = if (bites > 0) weightGrams / bites else 0.0
    val avgEnergyKj: Double get() = if (bites > 0) energyKj / bites else 0.0
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

    /**
     * 带**状态码**的读取：失败时也要拿到响应体。
     *
     * 账号接口必须能看到失败原因（"用户名已注册"/"密码不正确"），而 [read] 在非 2xx 时
     * 直接把 body 丢掉 —— 那是给"探测服务器是否可用"用的语义，这里不能复用。
     */
    private fun readWithCode(connection: HttpURLConnection): Pair<Int, String?> = try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        code to stream?.bufferedReader()?.use(BufferedReader::readText)
    } catch (e: Exception) {
        -1 to null
    } finally {
        connection.disconnect()
    }

    /** 带令牌的 GET（账号接口用）。 */
    fun getJsonAuth(base: String, path: String, token: String, timeoutMs: Int = 8000): Pair<Int, JSONObject?> {
        return try {
            val connection = open(base + path, timeoutMs)
            connection.requestMethod = "GET"
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            val (code, body) = readWithCode(connection)
            code to (body?.let { runCatching { JSONObject(it) }.getOrNull() })
        } catch (e: Exception) {
            -1 to null
        }
    }

    /** 带令牌的 POST。 */
    fun postJsonAuth(
        base: String,
        path: String,
        body: JSONObject,
        token: String = "",
        timeoutMs: Int = 15000,
    ): Pair<Int, JSONObject?> {
        return try {
            val connection = open(base + path, timeoutMs)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val (code, text) = readWithCode(connection)
            code to (text?.let { runCatching { JSONObject(it) }.getOrNull() })
        } catch (e: Exception) {
            -1 to null
        }
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

    /**
     * 带令牌读 bootstrap：登录用户的 `user` 字段来自真实账号。
     *
     * 拿不到就回落到不带令牌的那次请求 —— 服务器暂时没升级、或者令牌过期，
     * 都不该让"读菜品库"这件事失败。
     */
    fun fetchBootstrapAuthed(base: String, token: String): Bootstrap? {
        if (token.isBlank()) return fetchBootstrap(base)
        val (code, json) = getJsonAuth(base, "/api/bootstrap", token, 6000)
        if (code !in 200..299 || json == null) return fetchBootstrap(base)
        val data = json.optJSONObject("data") ?: return fetchBootstrap(base)
        return parseBootstrap(data)
    }

    /* ------------------------------------------------- 云端数据（记录 / 次数） */

    /**
     * 上报本地用餐记录，返回**服务器合并之后**的完整列表（拿它覆盖本地）。
     *
     * 返回 null 表示这次没成功（没登录 / 网络不通 / 服务器还是旧版没有这个接口）——
     * 调用方据此决定"这次就别覆盖本地了"，避免把用户记录清空。
     */
    fun uploadMeals(base: String, token: String, records: List<CloudRecord>): List<CloudRecord>? {
        if (token.isBlank()) return null
        val array = JSONArray()
        records.forEach { record ->
            val bites = JSONArray()
            record.bites.forEach { bite ->
                bites.put(JSONObject()
                    .put("dish", bite.dish)
                    .put("weight", bite.weight)
                    .put("energy", bite.energy)
                    .put("at", bite.at))
            }
            array.put(JSONObject()
                .put("key", record.key)
                .put("name", record.name)
                .put("startedAt", record.startedAt)
                .put("endedAt", record.endedAt)
                .put("bites", bites))
        }
        val (code, json) = postJsonAuth(base, "/api/meals", JSONObject().put("records", array), token)
        if (code !in 200..299 || json == null || !json.optBoolean("ok")) return null
        return parseCloudRecords(json.optJSONArray("records"))
    }

    /** 取服务器上这个账号的全部用餐记录（登录后覆盖本地用）。 */
    fun fetchMeals(base: String, token: String): List<CloudRecord>? {
        if (token.isBlank()) return null
        val (code, json) = getJsonAuth(base, "/api/meals", token, 8000)
        if (code !in 200..299 || json == null || !json.optBoolean("ok")) return null
        return parseCloudRecords(json.optJSONArray("records"))
    }

    /**
     * 覆盖服务器上这个账号的食用次数。返回是否成功。
     *
     * 报的是**最终值**而不是增量：增量会在重复上报时把次数越滚越大（见服务器 `set_usages` 的注释）。
     */
    fun uploadUsages(base: String, token: String, usages: Map<String, Int>): Boolean {
        if (token.isBlank()) return false
        val body = JSONObject()
        usages.forEach { (name, times) -> if (times > 0) body.put(name, times) }
        val (code, json) = postJsonAuth(base, "/api/usages", JSONObject().put("usages", body), token)
        return code in 200..299 && json != null && json.optBoolean("ok")
    }

    /** 取服务器上这个账号的食用次数（菜名 → 次数）。 */
    fun fetchUsages(base: String, token: String): Map<String, Int>? {
        if (token.isBlank()) return null
        val (code, json) = getJsonAuth(base, "/api/usages", token, 8000)
        if (code !in 200..299 || json == null || !json.optBoolean("ok")) return null
        val obj = json.optJSONObject("usages") ?: return emptyMap()
        val map = mutableMapOf<String, Int>()
        obj.keys().forEach { name -> map[name] = obj.optInt(name, 0) }
        return map
    }

    private fun parseCloudRecords(array: JSONArray?): List<CloudRecord> {
        if (array == null) return emptyList()
        val list = mutableListOf<CloudRecord>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val endedAt = item.optLong("endedAt", 0L)
            if (endedAt <= 0L) continue
            val bites = mutableListOf<Bite>()
            item.optJSONArray("bites")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val bite = arr.optJSONObject(j) ?: continue
                    bites.add(
                        Bite(
                            dish = bite.optString("dish"),
                            weight = bite.optDouble("weight", 0.0),
                            energy = bite.optDouble("energy", 0.0),
                            at = bite.optLong("at", 0L),
                        )
                    )
                }
            }
            list.add(
                CloudRecord(
                    key = item.optString("key").ifBlank { endedAt.toString() },
                    name = item.optString("name").ifBlank { "本餐" },
                    startedAt = item.optLong("startedAt", 0L).takeIf { it > 0 } ?: endedAt,
                    endedAt = endedAt,
                    bites = bites,
                )
            )
        }
        return list
    }

    /* ---------------------------------------------------------------- 账号 */

    /** 从账号接口的响应里取用户信息 + 令牌；失败时给出人话。 */
    private fun authResult(code: Int, json: JSONObject?): AuthResult {
        if (json == null) {
            return AuthResult.Fail(
                if (code <= 0) "连不上服务器，请检查网络与「服务器地址」"
                else "服务器返回了无法解析的内容（HTTP $code）"
            )
        }
        if (!json.optBoolean("ok")) {
            val message = json.optString("error").ifBlank { "操作失败（HTTP $code）" }
            return AuthResult.Fail(message)
        }
        val user = json.optJSONObject("user")
            ?: return AuthResult.Fail("服务器没有返回用户信息")
        val token = json.optString("token")
        val username = user.optString("username").ifBlank { user.optString("name") }
        if (username.isBlank()) return AuthResult.Fail("服务器返回的用户名为空")
        return AuthResult.Ok(
            Account(
                username = username,
                name = user.optString("name").ifBlank { username },
                avatar = user.optString("avatar", "avatar.svg"),
                token = token,
            )
        )
    }

    /** 注册（成功后服务器直接发令牌，等于同时登录）。 */
    fun register(base: String, username: String, password: String, name: String = ""): AuthResult {
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("name", name.trim())
        val (code, json) = postJsonAuth(base, "/api/auth/register", body)
        return authResult(code, json)
    }

    /** 登录。 */
    fun login(base: String, username: String, password: String): AuthResult {
        val body = JSONObject().put("username", username.trim()).put("password", password)
        val (code, json) = postJsonAuth(base, "/api/auth/login", body)
        return authResult(code, json)
    }

    /** 校验令牌是否还有效（App 启动时用）。返回 null = 网络不通（不该因此清掉登录态）。 */
    fun checkSession(base: String, token: String): Boolean? {
        val (code, json) = getJsonAuth(base, "/api/auth/me", token, 6000)
        if (code <= 0 || json == null) return null
        if (code in 200..299 && json.optBoolean("ok")) return true
        // 明确被服务器拒绝（401）才算失效
        return if (code == 401) false else null
    }

    fun logout(base: String, token: String) {
        postJsonAuth(base, "/api/auth/logout", JSONObject(), token, 6000)
    }

    /** 改昵称（服务器侧）。成功后返回带新昵称的 [Account]。 */
    fun rename(base: String, token: String, name: String): AuthResult {
        val current = State.account ?: return AuthResult.Fail("当前没有登录")
        val (code, json) = postJsonAuth(
            base, "/api/auth/rename", JSONObject().put("name", name.trim()), token
        )
        if (json == null || !json.optBoolean("ok")) {
            val message = json?.optString("error").orEmpty().ifBlank { "改昵称失败（HTTP $code）" }
            return AuthResult.Fail(message)
        }
        val serverName = json.optJSONObject("user")?.optString("name").orEmpty()
        return AuthResult.Ok(
            Account(
                username = current.username,
                name = serverName.ifBlank { name.trim() },
                avatar = current.avatar,
                token = current.token,
            )
        )
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

    /** 设备管理 → 点设备行：改这台勺子的名字（[deviceId] 是 MAC 或服务器设备 id）。 */
    data class RenameDevice(val deviceId: String) : AppDialog()

    /** 设置 → 杂项：服务器地址。 */
    object Server : AppDialog()

    /** 用餐结果 → 修正数据：field = duration / bites / weight / energy。 */
    data class EditResult(val field: String) : AppDialog()

    /** 用餐结果 → 每口详细数据（本次用餐）。 */
    object BiteDetail : AppDialog()

    /** 用餐记录 → 某条记录的明细。 */
    data class MealDetail(val record: MealRecord) : AppDialog()

    /**
     * 用餐结束后给这一餐起个名字。
     *
     * 只在"自定义本餐菜单"这种没有现成菜单名的情况下自动弹出（默认值「自定义菜单」）；
     * 用「使用现有菜单快速开始」的用餐不会弹（那份菜单本来就有名字）。
     */
    object MealName : AppDialog()

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

/* ------------------------------------------------------------------ 账号 */

/**
 * 账号接口的结果。
 *
 * 用密封类而不是抛异常：登录失败（密码错、用户名被占）是**正常的业务分支**，
 * 界面要拿它显示一句人话，而不是当作崩溃来处理。
 */
sealed class AuthResult {
    /** 成功。[user] 是公开信息，[token] 是登录令牌。 */
    data class Ok(val user: Account) : AuthResult()

    /** 失败。[message] 是服务器给的人话（如"用户名或密码不正确"）。 */
    data class Fail(val message: String) : AuthResult()
}

/**
 * 用餐中的实时数值。
 *
 * 字段用 Compose 的 `mutableStateOf` 承载：写入方（服务器轮询线程、计时器）完全不用改，
 * 读取方在 Compose 里会自动重组。
 */
class MealState {
    var minutes by mutableStateOf(0)
    var food by mutableStateOf("小米粥")

    /**
     * 勺中实时读数，**基准单位 g**。
     *
     * 以前是 Int —— 那时只有服务器模拟器在推数据（它本来就是整数克）。接上真机之后
     * 这个假设就不成立了：HX711 的分辨率到 0.1 g，固件也是按 `重量×10` 发过来的。
     * 存成 Int 会把小数直接抹掉（12.7 g 变成 12 g），并且和 `Bite.weight`（Double）
     * 之间来回转换，所以这里统一改成 Double。
     */
    var spoonWeight by mutableStateOf(0.0)
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
    private const val KEY_BLE_ADDRESS = "ble_spoon_address"
    private const val KEY_BLE_NAME = "ble_spoon_name"
    private const val KEY_BLE_SAVED = "ble_spoon_saved"
    private const val KEY_BLE_NAMES = "ble_spoon_names"
    private const val KEY_SHOW_REMIND = "show_meal_remind"
    private const val KEY_ACCOUNT_USER = "account_username"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_ACCOUNT_AVATAR = "account_avatar"
    private const val KEY_ACCOUNT_TOKEN = "account_token"
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
    /**
     * 勺子数据是否在线。
     *
     * 只有一个来源能把它点亮：**真机蓝牙**（[SpoonLink] 收到状态帧）。
     * 服务器模拟器那条路已经删掉了（勺子数据只来自真机）。
     */
    var deviceOnline by mutableStateOf(false)

    /**
     * 现在是不是在「菜单编辑」页里选菜。
     *
     * 为什么需要这个标记：菜单编辑页复用了加菜页的整套列表（搜索/分类/排序/勾选），
     * 但**列菜单不是用餐流程**，那一页顶上不该出现「餐前设置 → 用餐中 → 用餐结果」的步骤条
     * （它会让用户以为"我正在开始一餐"）。所以选菜列表按这个标记决定画不画步骤条。
     */
    var editingMenu by mutableStateOf(false)

    /** 蓝牙连接状态机（设备管理 / 连接弹窗直接显示它）。 */
    var bleStatus by mutableStateOf(SpoonLink.Status.IDLE)

    /** 蓝牙那条路上"现在该告诉用户的一句话"（失败原因 / 正在扫描…）；null = 没什么可说。 */
    var bleHint by mutableStateOf<String?>(null)

    var loadError by mutableStateOf<String?>(null)
    var data by mutableStateOf<Bootstrap?>(null)

    /**
     * 正在「用餐记录详情页」里编辑的那条记录；null = 不在编辑。
     *
     * 点记录列表里的一行就把它装上并打开 [ui.MealDetailActivity] ——
     * 与用餐结果页同一套界面，所以用户能直接看懂并修改（时长/口数/重量/热量/名称）。
     */
    var mealEditor by mutableStateOf<MealEditor?>(null)

    var tab by mutableStateOf(0)      // 0 快速开始 / 1 收藏食物 / 2 用餐记录 / 3 设置
    var screen by mutableStateOf<Screen>(Screen.QuickStart)

    var settingsView by mutableStateOf("")
    /** 设备管理页的作用域：`saved`（已保存的智味勺）/ `nearby`（附近的智味勺）。 */
    var deviceScope by mutableStateOf("saved")
    var statsPage by mutableStateOf(1)

    var category by mutableStateOf("全部")
    var query by mutableStateOf("")
    var folderId by mutableStateOf<String?>(null)
    val selected = mutableStateListOf<String>()

    /*
     * 三处列表各自的排序方式与方向。
     *
     * 为什么要**分开三份**状态、而不是共用一个 `sortKey`：这三张列表能选的排序维度本来就不同
     * （收藏与菜品没有"用餐时间"，用餐记录没有"能量密度"），共用一个键会出现
     * "在记录页选了按热量，切到菜品页变成非法值"这种隐含耦合。分开之后每页记得住自己的选择。
     */
    var favoriteSort by mutableStateOf(FavoriteSort.FAVORITE_TIME)
    var favoriteDesc by mutableStateOf(true)
    var foodSort by mutableStateOf(FoodSort.TIMES)
    var foodDesc by mutableStateOf(true)
    var recordSort by mutableStateOf(RecordSort.TIME)
    var recordDesc by mutableStateOf(true)


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

    /* ---------------------------------------------------------------- 账号 */

    /**
     * 当前登录的账号；null = 未登录（离线使用）。
     *
     * 登录态存在偏好里（用户名 / 昵称 / 令牌），**不存密码**。
     * 启动时先用它把界面点亮，再后台找服务器校验一次（见 `AppCore.verifySession`）。
     */
    var account by mutableStateOf<Account?>(null)
        private set

    /** 账号操作是否正在进行（界面据此禁用按钮、显示转圈）。 */
    var accountBusy by mutableStateOf(false)

    /** 账号操作的结果提示（一句话，显示在登录页上）。 */
    var accountMessage by mutableStateOf<String?>(null)

    /**
     * 直接替换内存里的账号对象（不改偏好）。
     *
     * 名字刻意不叫 `setAccount` —— 那会和 `account` 的 setter 撞成同一个 JVM 签名
     * （Kotlin 会报 `Platform declaration clash`）。要落盘请用 [rememberAccount]。
     */
    fun updateAccountInMemory(value: Account?) {
        account = value
    }

    /** 登录成功后记住账号（令牌落偏好，密码从不落盘）。 */
    fun rememberAccount(context: Context, value: Account) {
        account = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCOUNT_USER, value.username)
            .putString(KEY_ACCOUNT_NAME, value.name)
            .putString(KEY_ACCOUNT_AVATAR, value.avatar)
            .putString(KEY_ACCOUNT_TOKEN, value.token)
            .apply()
    }

    /** 退出登录：清掉内存与偏好的登录态，但**保留本地数据**（那个账号的库文件还在）。 */
    fun forgetAccount(context: Context) {
        account = null
        accountMessage = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACCOUNT_USER)
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_AVATAR)
            .remove(KEY_ACCOUNT_TOKEN)
            .apply()
    }

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
        // 蓝牙：上次连过的勺子（自动重连用）与已保存的勺子
        bleSpoonAddress = prefs.getString(KEY_BLE_ADDRESS, "") ?: ""
        bleSpoonName = prefs.getString(KEY_BLE_NAME, "") ?: ""
        savedBle = prefs.getStringSet(KEY_BLE_SAVED, emptySet())?.toMutableSet() ?: mutableSetOf()
        // 用户给勺子起的名字（MAC -> 名字）
        bleNames = prefs.getStringSet(KEY_BLE_NAMES, emptySet())
            ?.mapNotNull { entry ->
                val at = entry.indexOf('|')
                if (at <= 0) null else entry.substring(0, at) to entry.substring(at + 1)
            }?.toMap()?.toMutableMap() ?: mutableMapOf()
        // 用餐提醒弹窗：用户点过一次「我已知晓」之后就不再弹（可在设置里重新打开）
        showMealRemind = prefs.getBoolean(KEY_SHOW_REMIND, true)
        // 登录态：只恢复"上次登录的账号"，令牌的有效性由 AppCore 找服务器校验
        val savedUser = prefs.getString(KEY_ACCOUNT_USER, "") ?: ""
        val savedToken = prefs.getString(KEY_ACCOUNT_TOKEN, "") ?: ""
        account = if (savedUser.isNotBlank() && savedToken.isNotBlank()) {
            Account(
                username = savedUser,
                name = prefs.getString(KEY_ACCOUNT_NAME, "") ?: "",
                avatar = prefs.getString(KEY_ACCOUNT_AVATAR, "avatar.svg") ?: "avatar.svg",
                token = savedToken,
            )
        } else {
            null
        }
    }

    /* -------------------------------------------------------------- 蓝牙 */

    /** 上次连接成功的勺子地址（空 = 没连过）。用于「自动连接」。 */
    var bleSpoonAddress by mutableStateOf("")

    /** 上次连接成功的勺子名字（只用于界面显示）。 */
    var bleSpoonName by mutableStateOf("")

    /** 用户点过「保存」的真机勺子地址集合（设置 → 设备管理里的"已保存"）。 */
    var savedBle by mutableStateOf<MutableSet<String>>(mutableSetOf())
        private set

    /** 用户给勺子改过的名字（MAC -> 名字）。没改过就用广播名/MAC 后 5 位。 */
    var bleNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * 改一台勺子的名字。
     *
     * 同时更新 [bleNames]（下次扫描/连接时显示用）与 [bleSpoonName]（自动回连时显示用），
     * 并把"上次连过"这个名字一起写进偏好 —— 否则重启后自动回连又变回广播名。
     */
    fun renameSpoon(context: Context, address: String, name: String) {
        val clean = name.trim()
        bleNames = if (clean.isEmpty()) bleNames - address else bleNames + (address to clean)
        if (bleSpoonAddress == address) bleSpoonName = clean
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_BLE_NAMES, bleNames.map { "${it.key}|${it.value}" }.toSet())
            .putString(KEY_BLE_NAME, if (bleSpoonAddress == address) clean else bleSpoonName)
            .apply()
    }

    /** 用餐提醒弹窗是否要弹（用户点过一次「我已知晓」之后就不再弹）。 */
    var showMealRemind by mutableStateOf(true)

    fun setShowMealRemind(context: Context, show: Boolean) {
        showMealRemind = show
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_REMIND, show)
            .apply()
    }

    /**
     * 记住这台真机勺子（连接成功后调用）：下次自动连接 + 出现在「已保存的智味勺」里。
     */
    fun rememberSpoon(context: Context, address: String, name: String) {
        // 用户改过名字就用他的名字，别被广播名覆盖
        val finalName = bleNames[address]?.takeIf { it.isNotBlank() } ?: name
        bleSpoonAddress = address
        bleSpoonName = finalName
        savedBle = (savedBle + address).toMutableSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BLE_ADDRESS, address)
            .putString(KEY_BLE_NAME, finalName)
            .putStringSet(KEY_BLE_SAVED, savedBle)
            .apply()
    }

    /** 保存 / 取消保存一台真机勺子。 */
    fun setSpoonSaved(context: Context, address: String, saved: Boolean) {
        savedBle = (if (saved) savedBle + address else savedBle - address).toMutableSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_BLE_SAVED, savedBle)
            .apply()
    }

    /**
     * 忘掉这台勺子：既取消保存，也清掉"上次连过"的地址。
     *
     * 两件事必须一起做 —— 自动回连读的是 [bleSpoonAddress]，只清 [savedBle] 的话
     * 用户点了「取消保存」，下次打开应用它还是会自己连上来。
     */
    fun forgetSpoon(context: Context, address: String) {
        savedBle = (savedBle - address).toMutableSet()
        val clearLast = bleSpoonAddress == address
        if (clearLast) {
            bleSpoonAddress = ""
            bleSpoonName = ""
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_BLE_SAVED, savedBle)
        if (clearLast) {
            editor.putString(KEY_BLE_ADDRESS, "").putString(KEY_BLE_NAME, "")
        }
        editor.apply()
    }

    /** 蓝牙权限全部拿到了吗（按系统版本分支，见 `AndroidManifest.xml` 里的声明）。 */
    fun hasBlePermissions(context: Context): Boolean {
        fun granted(permission: String) =
            context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            granted(android.Manifest.permission.BLUETOOTH_SCAN) &&
                granted(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // 12 以下：安装期权限 + 运行时定位（BLE 扫描的老模型要求）
            granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** 当前系统版本需要申请的蓝牙相关运行时权限。 */
    fun bleRuntimePermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
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

    /**
     * 当前连接的勺子。
     *
     * 真机（BLE）与服务器模拟设备都从 `data.devices` 里找：BLE 设备由
     * [BleSpoon.activeDevice] 在连接成功时**写进这份列表**，所以界面（设备管理、
     * 连接弹窗、"当前已连接"那一行）不需要为两种来源写两套代码。
     */
    fun connectedDevice(): Device? = data?.devices?.firstOrNull { it.id == connectedId }

    /**
     * 菜品列表的排序方式（自定义本餐菜单 / 菜品库列表）。
     *
     * [label] 是界面上显示的中文名；[compare] 给出"升序"下的比较规则，
     * 倒序统一由 [State.applyFoodSort] 反转 —— 这样每个维度只需要写一遍。
     */
    enum class FoodSort(val label: String, val compare: Comparator<Food>) {
        ALPHABET("按字母顺序", compareBy { it.name.lowercase() }),
        FAVORITE_TIME("按收藏时间", compareBy { it.favoriteAtMs }),
        TIMES("按食用次数", compareBy { it.times }),
        DENSITY("按能量密度", compareBy { it.density }),
    }

    /** 收藏食物列表的排序方式：与菜品列表同一批维度（收藏夹就是菜品库的收藏子集）。 */
    enum class FavoriteSort(val label: String, val compare: Comparator<Food>) {
        ALPHABET("按字母顺序", compareBy { it.name.lowercase() }),
        FAVORITE_TIME("按收藏时间", compareBy { it.favoriteAtMs }),
        TIMES("按食用次数", compareBy { it.times }),
        DENSITY("按能量密度", compareBy { it.density }),
    }

    /**
     * 用餐记录的排序方式。
     *
     * 注意 [TIME] 的标签是"按用餐时间/序号"：记录列表里的 `#N` 序号是按**完成时间**从早到晚编的
     * （见 [Store.meals]），所以"按序号"与"按用餐时间"本来就是同一个顺序，做成一个选项。
     * 升序 = 序号从小到大（也就是时间从早到晚）。
     */
    enum class RecordSort(val label: String, val compare: Comparator<MealRecord>) {
        TIME("按用餐时间/序号", compareBy { it.endedAt }),
        ALPHABET("按字母顺序", compareBy { it.menu.lowercase() }),
        WEIGHT("按食用重量", compareBy { it.weight }),
        ENERGY("按食用热量", compareBy { it.energy }),
    }

    /** 收藏列表（已排序）：只含被收藏的菜。 */
    fun sortedFavorites(): List<Food> {
        val times = data?.favoriteTimes ?: emptyMap()
        val list = (data?.foods ?: emptyList()).filter { times.containsKey(it.id) }
        return applyFavoriteSort(list)
    }

    fun applyFavoriteSort(list: List<Food>): List<Food> {
        val sorted = if (favoriteSort == FavoriteSort.FAVORITE_TIME) {
            /*
             * 收藏时间排在 Food 对象上（读库时由 Store 填好），但老库里可能有 favorit=1
             * 而时间戳为 0 的行（v1 升级上来的文本没解析出来）。它们按 0 排在最前面会显得莫名其妙，
             * 所以统一挪到最后。
             */
            list.sortedWith(
                compareBy({ it.favoriteAtMs <= 0L }, { it.favoriteAtMs }),
            )
        } else {
            list.sortedWith(favoriteSort.compare)
        }
        return if (favoriteDesc) sorted.reversed() else sorted
    }

    fun applyFoodSort(list: List<Food>): List<Food> {
        val sorted = list.sortedWith(foodSort.compare)
        return if (foodDesc) sorted.reversed() else sorted
    }

    fun sortedFoods(): List<Food> = applyFoodSort(data?.foods ?: emptyList())

    fun filteredFoods(): List<Food> {
        var list = sortedFoods()
        if (category != "全部") list = list.filter { it.category == category }
        if (query.isNotEmpty()) list = list.filter { it.name.contains(query, ignoreCase = true) }
        return list
    }

    fun sortedRecords(): List<MealRecord> {
        val sorted = (data?.records ?: emptyList()).sortedWith(recordSort.compare)
        return if (recordDesc) sorted.reversed() else sorted
    }

    /**
     * 收藏夹里的分类文件夹，**按字母顺序**排列（需求明确要求这一步不受正序/倒序影响）。
     *
     * "其他"排最后：它是"没归类"的兜底，不是与其他分类并列的一项。
     */
    fun favoriteFolders(): List<Pair<String, Int>> {
        val favorites = sortedFavorites()
        val names = favorites.map { it.category }.filter { it.isNotBlank() }.distinct()
        val sorted = names.sortedWith(compareBy({ it == "其他" }, { it.lowercase() }))
        return sorted.map { name -> name to favorites.count { it.category == name } }
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
